/*
 * Autopsy Forensic Browser
 * 
 * Copyright 2014 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.ingest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.Content;

/**
 * A file ingest pipeline composed of a sequence of file ingest modules
 * constructed from ingest module templates.
 */
final class FileIngestPipeline {

    private static final Logger logger = Logger.getLogger(FileIngestPipeline.class.getName());
    private final IngestJob job;
    private final List<IngestModuleTemplate> moduleTemplates;
    private List<FileIngestModuleDecorator> modules = new ArrayList<>();

    FileIngestPipeline(IngestJob task, List<IngestModuleTemplate> moduleTemplates) {
        this.job = task;
        this.moduleTemplates = moduleTemplates;
    }

    List<IngestModuleError> startUp() {
        List<IngestModuleError> errors = new ArrayList<>();
        // Create an ingest module instance from each ingest module template
        // that has an ingest module factory capable of making data source
        // ingest modules. Map the module class names to the module instance
        // to allow the modules to be put in the sequence indicated by the
        // ingest pipelines configuration.
        Map<String, FileIngestModuleDecorator> modulesByClass = new HashMap<>();
        for (IngestModuleTemplate template : moduleTemplates) {
            if (template.isFileIngestModuleTemplate()) {
                FileIngestModuleDecorator module = new FileIngestModuleDecorator(template.createFileIngestModule(), template.getModuleName());
                IngestJobContext context = new IngestJobContext(job);
                try {
                    module.startUp(context);
                    modulesByClass.put(module.getClassName(), module);
                    IngestManager.fireModuleEvent(IngestManager.IngestEvent.STARTED.toString(), template.getModuleName());
                } catch (Exception ex) {
                    errors.add(new IngestModuleError(module.getDisplayName(), ex));
                }
            }
        }
        // Establish the module sequence of the core ingest modules
        // indicated by the ingest pipeline configuration, adding any
        // additional modules found in the global lookup to the end of the
        // pipeline in arbitrary order.
        List<String> pipelineConfig = IngestPipelinesConfiguration.getInstance().getFileIngestPipelineConfig();
        for (String moduleClassName : pipelineConfig) {
            if (modulesByClass.containsKey(moduleClassName)) {
                modules.add(modulesByClass.remove(moduleClassName));
            }
        }
        for (FileIngestModuleDecorator module : modulesByClass.values()) {
            modules.add(module);
        }
        return errors;
    }

    List<IngestModuleError> process(AbstractFile file) {
        List<IngestModuleError> errors = new ArrayList<>();
        Content dataSource = this.job.getDataSource();
        logger.log(Level.INFO, String.format("Processing {0} from {1}", file.getName(), dataSource.getName()));
        for (FileIngestModuleDecorator module : this.modules) {
            try {
                module.process(file);
            } catch (Exception ex) {
                errors.add(new IngestModuleError(module.getDisplayName(), ex));
            }
            if (job.isCancelled()) {
                break;
            }
        }
        file.close();
        IngestManager.fireFileDone(file.getId());
        return errors;
    }

    List<IngestModuleError> shutDown(boolean ingestJobCancelled) {
        List<IngestModuleError> errors = new ArrayList<>();
        for (FileIngestModuleDecorator module : this.modules) {
            try {
                module.shutDown(ingestJobCancelled);
            } catch (Exception ex) {
                errors.add(new IngestModuleError(module.getDisplayName(), ex));
            } finally {
                IngestManager.fireModuleEvent(IngestManager.IngestEvent.COMPLETED.toString(), module.getDisplayName());
            }
        }
        return errors;
    }

    private static class FileIngestModuleDecorator implements FileIngestModule {

        private final FileIngestModule module;
        private final String displayName;

        FileIngestModuleDecorator(FileIngestModule module, String displayName) {
            this.module = module;
            this.displayName = displayName;
        }

        String getClassName() {
            return module.getClass().getCanonicalName();
        }

        String getDisplayName() {
            return displayName;
        }

        @Override
        public void startUp(IngestJobContext context) throws IngestModuleException {
            module.startUp(context);
        }

        @Override
        public IngestModule.ProcessResult process(AbstractFile file) {
            return module.process(file);
        }

        @Override
        public void shutDown(boolean ingestJobWasCancelled) {
            module.shutDown(ingestJobWasCancelled);
        }
    }
}
