/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.recentactivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.sleuthkit.autopsy.coreutils.Logger;
import javax.swing.JPanel;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.sleuthkit.autopsy.coreutils.PlatformUtil;
import org.sleuthkit.autopsy.ingest.IngestImageWorkerController;
import org.sleuthkit.autopsy.ingest.IngestModuleImage;
import org.sleuthkit.autopsy.ingest.IngestModuleInit;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.autopsy.ingest.ModuleDataEvent;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.FsContent;
import org.sleuthkit.datamodel.Image;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * This module attempts to extract web queries from major search engines by
 * querying the blackboard for web history and bookmark artifacts, and
 * extracting search text from them.
 * 
*
 * To add search engines, edit SearchEngines.xml under RecentActivity
 * 
*/
public class SearchEngineURLQueryAnalyzer extends Extract implements IngestModuleImage {

    private IngestServices services;
    static final String MODULE_NAME = "Search Engine Query Analyzer";
    public static final String XMLFile = "SEQUAMappings.xml";
    private static String[] searchEngineNames;
    private static SearchEngine[] engines;
    private static Document xmlinput;
    private static final SearchEngine NullEngine = new SearchEngine("NONE", "NONE", new HashMap<String, String>());

    SearchEngineURLQueryAnalyzer() {
    }

    private static class SearchEngine {

        private String _engineName;
        private String _domainSubstring;
        private Map<String, String> _splits;
        private int _count;

        SearchEngine(String engineName, String domainSubstring, Map<String, String> splits) {
            _engineName = engineName;
            _domainSubstring = domainSubstring;
            _splits = splits;
            _count = 0;
        }

        public void increment() {
            ++_count;
        }

        public String getEngineName() {
            return _engineName;
        }

        public String getDomainSubstring() {
            return _domainSubstring;
        }

        public int getTotal() {
            return _count;
        }

        public Set<Map.Entry<String, String>> getSplits() {
            return this._splits.entrySet();
        }

        @Override
        public String toString() {
            String split = " ";
            for (Map.Entry<String, String> kvp : getSplits()) {
                split = split + "[ " + kvp.getKey() + " :: " + kvp.getValue() + " ]" + ", ";
            }
            return "Name: " + _engineName + "\n Domain Substring: " + _domainSubstring + "\n count: " + _count + "\n Split Tokens: \n " + split;
        }
    }

    private void createEngines() {
        NodeList nlist = xmlinput.getElementsByTagName("SearchEngine");
        SearchEngine[] listEngines = new SearchEngine[nlist.getLength()];
        for (int i = 0; i < nlist.getLength(); i++) {
            try {
                NamedNodeMap nnm = nlist.item(i).getAttributes();

                String EngineName = nnm.getNamedItem("engine").getNodeValue();
                String EnginedomainSubstring = nnm.getNamedItem("domainSubstring").getNodeValue();
                Map<String, String> splits = new HashMap<String, String>();

                NodeList listSplits = xmlinput.getElementsByTagName("splitToken");
                for (int k = 0; k < listSplits.getLength(); k++) {
                    if (listSplits.item(k).getParentNode().getAttributes().getNamedItem("engine").getNodeValue().equals(EngineName)) {
                        splits.put(listSplits.item(k).getAttributes().getNamedItem("plainToken").getNodeValue(), listSplits.item(k).getAttributes().getNamedItem("regexToken").getNodeValue());
                    }
                }
                SearchEngine Se = new SearchEngine(EngineName, EnginedomainSubstring, splits);
                System.out.println(Se.toString());
                listEngines[i] = Se;
            } catch (Exception e) {
                System.out.println(e.toString());
            }
        }
        engines = listEngines;
    }

    /**
     * Returns which of the supported SearchEngines, if any, the given string
     * belongs to.
     *     
     * @param domain domain as part of the URL
     * @return supported search engine the domain belongs to, if any
     *     
     */
    private static SearchEngine getSearchEngine(String domain) {
        for (int i = 0; i < engines.length; i++) {
            if (domain.contains(engines[i].getDomainSubstring())) {
                return engines[i];
            }
        }
        return SearchEngineURLQueryAnalyzer.NullEngine;
    }

    private void getSearchEngineNames() {
        String[] listNames = new String[engines.length];
        for (int i = 0; i < listNames.length; i++) {
            listNames[i] = engines[i]._engineName;
        }
        searchEngineNames = listNames;
    }

    /**
     * Attempts to extract the query from a URL.
     *     
     * @param url The URL string to be dissected.
     * @return The extracted search query.
     */
    private String extractSearchEngineQuery(String url) {
        String x = "NoQuery";
        SearchEngine eng = getSearchEngine(url);
        for (Map.Entry<String, String> kvp : eng.getSplits()) {
            if (url.contains(kvp.getKey())) {
                x = split2(url, kvp.getValue());
                break;
            }
        }
        try { //try to decode the url
            String decoded = URLDecoder.decode(x, "UTF-8");
            return decoded;
        } catch (UnsupportedEncodingException uee) { //if it fails, return the encoded string
            logger.warning("Error during URL decoding: " + uee);
            return x;
        }
    }

    /**
     * Splits URLs based on a delimeter (key). .contains() and .split()
     *     
* @param url The URL to be split
     * @param kvp the delimeter key value pair used to split the URL into its
     * search, extracted from the url type. query.
     * @return The extracted search query
     *     
*/
    private String split2(String url, String value) {
        String basereturn = "NoQuery";
        String v = value;
        //Want to determine if string contains a string based on splitkey, but we want to split the string on splitKeyConverted due to regex
        if (value.contains("\\?")) {
            v = value.replace("\\?", "?");
        }
        String[] sp = url.split(v);
        if (sp.length >= 2) {
            if (sp[sp.length - 1].contains("&")) {
                basereturn = sp[sp.length - 1].split("&")[0];
            } else {
                basereturn = sp[sp.length - 1];
            }
        }
        return basereturn;
    }

    private void getURLs(Image image, IngestImageWorkerController controller) {
        int totalQueries = 0;
        try {
            //from blackboard_artifacts
            Collection<BlackboardArtifact> listArtifacts = currentCase.getSleuthkitCase().getMatchingArtifacts("WHERE (`artifact_type_id` = '" + ARTIFACT_TYPE.TSK_WEB_BOOKMARK.getTypeID()
                    + "' OR `artifact_type_id` = '" + ARTIFACT_TYPE.TSK_WEB_HISTORY.getTypeID() + "') "); //List of every 'web_history' and 'bookmark' artifact
            logger.info("Processing " + listArtifacts.size() + " blackboard artifacts.");
            getAll:
            for (BlackboardArtifact artifact : listArtifacts) {
                //initializing default attributes
                String source = ""; //becomes "bookmark" if attribute type is bookmark, remains blank otherwise
                String query = "";
                String searchEngineDomain = "";
                String browser = "";
                long last_accessed = -1;
                //from tsk_files
                FsContent fs = this.extractFiles(image, "select * from tsk_files where `obj_id` = '" + artifact.getObjectID() + "'").get(0); //associated file
                SearchEngine se = NullEngine;
                //from blackboard_attributes
                Collection<BlackboardAttribute> listAttributes = currentCase.getSleuthkitCase().getMatchingAttributes("Where `artifact_id` = " + artifact.getArtifactID());
                getAttributes:
                for (BlackboardAttribute attribute : listAttributes) {
                    if (controller.isCancelled()) {
                        break getAll; //User cancled the process.
                    }
                    if (attribute.getAttributeTypeID() == BlackboardAttribute.ATTRIBUTE_TYPE.TSK_URL.getTypeID()) {
                        final String urlString = attribute.getValueString();
                        se = getSearchEngine(urlString);
                        if (!se.equals(NullEngine)) {
                            query = extractSearchEngineQuery(attribute.getValueString());
                            if (query.equals("NoQuery") || query.equals("")) { //False positive match, artifact was not a query.
                                break getAttributes;
                            }
                        } else if (se.equals(NullEngine)) {
                            break getAttributes; //could not determine type. Will move onto next artifact
                        }
                    } else if (attribute.getAttributeTypeID() == BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()) {
                        browser = attribute.getValueString();
                    } else if (attribute.getAttributeTypeID() == BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DOMAIN.getTypeID()) {
                        searchEngineDomain = attribute.getValueString();
                    } else if (attribute.getArtifactID() == BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_BOOKMARK.getTypeID()) {
                        source = "bookmark";
                    } else if (attribute.getAttributeTypeID() == BlackboardAttribute.ATTRIBUTE_TYPE.TSK_LAST_ACCESSED.getTypeID()) {
                        last_accessed = attribute.getValueLong();
                    }
                }

                if (!se.equals(NullEngine) && !query.equals("NoQuery") && !query.equals("")) {
                    try {

                        Collection<BlackboardAttribute> bbattributes = new ArrayList<BlackboardAttribute>();
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DOMAIN.getTypeID(), MODULE_NAME, searchEngineDomain));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_TEXT.getTypeID(), MODULE_NAME, query));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(), MODULE_NAME, source, browser));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_LAST_ACCESSED.getTypeID(), MODULE_NAME, last_accessed));
                        this.addArtifact(ARTIFACT_TYPE.TSK_WEB_SEARCH_QUERY, fs, bbattributes);
                        se.increment();
                        ++totalQueries;
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Error during add artifact.", e + " from " + fs.toString());
                        this.addErrorMessage(this.getName() + ": Error while adding artifact");
                    }
                    services.fireModuleDataEvent(new ModuleDataEvent("RecentActivity", BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_SEARCH_QUERY));
                }


            }
        } catch (Exception e) {
            logger.info("Encountered error retrieving artifacts: " + e);
        } finally {
            if (controller.isCancelled()) {
                logger.info("Operation terminated by user.");
            }
            logger.info("Extracted " + totalQueries + " queries from the blackboard");
        }
    }

    private String getTotals() {
        String total = "";
        for (SearchEngine se : engines) {
            total += se.getEngineName() + " : " + se.getTotal() + "\n";
        }
        return total;
    }

    @Override
    public void process(Image image, IngestImageWorkerController controller) {
        this.getURLs(image, controller);
        logger.info("Search Engine stats: \n" + getTotals());
    }

    @Override
    public void init(IngestModuleInit initContext) {
        String path = PlatformUtil.getUserDirectory().getAbsolutePath();
        File xmlfile = new File(path + File.separator + XMLFile);
        if (!xmlfile.exists()) {
            try {
                InputStream inputStream = SearchEngineURLQueryAnalyzer.class.getResourceAsStream(XMLFile);
                OutputStream out = new FileOutputStream(xmlfile);
                byte buf[] = new byte[1024];
                int len;
                while ((len = inputStream.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.close();
                inputStream.close();
            } catch (IOException ex) {
                Logger.getLogger(SearchEngineURLQueryAnalyzer.class.getName()).log(Level.SEVERE, "Failed to extract XML file", ex);
            }
            services = IngestServices.getDefault();
            logger.info("running init()");
        }
        init2();
    }

    private void init2() {
        try {
            logger.info("Running init");
            String path = PlatformUtil.getUserDirectory().getAbsolutePath() + File.separator + XMLFile;
            File f = new File(path);
            System.out.println("Load successful");
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document xml = db.parse(f);
            xmlinput = xml;
            try {
                createEngines();
                getSearchEngineNames();
            } catch (Exception e) {
                System.out.println("Unable to create Search Engines! \n " + e.toString());
            }
        } catch (Exception e) {
            System.out.println("Was not able to load file, error was: " + e.toString());
        }
    }

    @Override
    public void complete() {
        logger.info("running complete()");
    }

    @Override
    public void stop() {
        logger.info("running stop()");
    }

    @Override
    public String getName() {
        return this.moduleName;
    }

    @Override
    public String getDescription() {
        String total = "";
        for (String name : searchEngineNames) {
            total += name + "\n";
        }
        return "Extracts search queries on the following search engines: " + total;
    }

    @Override
    public ModuleType getType() {
        return ModuleType.Image;
    }

    @Override
    public boolean hasBackgroundJobsRunning() {
        return false;
    }

    @Override
    public boolean hasSimpleConfiguration() {
        return false;
    }

    @Override
    public boolean hasAdvancedConfiguration() {
        return false;
    }

    @Override
    public void saveSimpleConfiguration() {
    }

    @Override
    public void saveAdvancedConfiguration() {
    }

    @Override
    public JPanel getSimpleConfiguration() {
        return null;
    }

    @Override
    public JPanel getAdvancedConfiguration() {
        return null;
    }
}
