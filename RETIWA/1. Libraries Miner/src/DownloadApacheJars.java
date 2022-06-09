package com.anon.apacheapis;

import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Given a Maven "GroupID", this script downloads the jar for latest artifacts of the given group.
 * Note: You may receive timeout exceptions. It's normal and it means the jar is not available for whatever reason.
 */
public class DownloadApacheJars {
    private static final Logger logger = LoggerFactory.getLogger(DownloadApacheJars.class);

    static final String DOWNLOAD_DIR_PATH = "/Users/X/Downloads/libs/";

    static final String USER_AGENT = ("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6");
    static final String ARTIFACT_MOVED_NOTICE = "This artifact was moved to";

    public static void main(String[] args) {

        String groupID = "org.apache.commons";
        String mvnRepo_baseURL = "https://mvnrepository.com/artifact/"+groupID;
        boolean reachedLastPage = false;
        int page = 0;
        // Get List of artifacts
        logger.info("\n\n\n*** Getting ArtifactIDs ***");
        List<String> artifactIDs = new ArrayList<>();
        while(false == reachedLastPage)
        {
            page++;
            String url = String.format( "%s?p=%d",mvnRepo_baseURL, page);
            logger.info("Parsing page {} -> {}", page, url);
            Document doc = null;
            try {
                doc = Jsoup.connect(url).userAgent(USER_AGENT).get();
                Elements libraries = doc.select("#maincontent .im>a");
                if(libraries.size()==0) {
                    reachedLastPage = true;
                    logger.info("We stop iterating pages as we reached an empty page (p={}).", page);
                }
                for (Element lib : libraries) {
                    String href = lib.attr("href");
                    if(href.startsWith("/artifact/"+groupID+"/")==false)
                        continue; // not an artifact with our target groupID
                    String library_artifactId = href.substring(href.lastIndexOf('/')+1);
                    logger.info(library_artifactId);
                    artifactIDs.add(library_artifactId);
                }
            } catch (Exception e) {
                logger.error("",e);
            }
        }


        // Get List of JarDownloadList page of each artifact
        logger.info("\n\n\n*** Getting Download Pages ***");
        List<String> jarDownloadURLs = new ArrayList<>();
        for(String artifactID: artifactIDs) {
            String artifact_page = "https://mvnrepository.com/artifact/" + groupID + "/" + artifactID;
            Document doc = null;
            try {
                logger.info("Looking for latest version at {} ...", artifact_page);
                doc = Jsoup.connect(artifact_page).userAgent(USER_AGENT).get();
                Element mainContent = doc.selectFirst("#maincontent");
                if(mainContent.text().contains(ARTIFACT_MOVED_NOTICE))
                {
                    boolean success_finding_alternative_name = false;
                    boolean success_finding_alternative_name_but_different_groupID = false;
                    String newArtifactID=null;
                    Elements allDivs = mainContent.select("#maincontent div");
                    for(Element aDiv: allDivs)
                    {
                        if(aDiv.text().contains(ARTIFACT_MOVED_NOTICE))
                        {
                            success_finding_alternative_name = true;
                            Element movedToGroupID = aDiv.selectFirst("table tbody a:nth-child(1)");
                            if(movedToGroupID.attr("href").substring("/artifact/".length()).equals(groupID)==false) {
                                logger.warn("Skipping {}/{} as moved and new groupID is different", groupID, artifactID);
                                success_finding_alternative_name_but_different_groupID = true;
                            }
                            else {
                                Element movedToArtifactID = aDiv.selectFirst("table tbody a:nth-child(2)");
                                newArtifactID = movedToArtifactID.attr("href");
                                newArtifactID = newArtifactID.substring("/artifact/".length()+groupID.length() + 1);
                                logger.warn("Skipping {}/{} as moved to {}", groupID, artifactID, newArtifactID);
                            }
                            break;
                        }
                    }
                    if(success_finding_alternative_name==false)
                        logger.error("We couldn't identify new ArtitifactID for the following moved artifact: {}/{}", groupID, artifactID);
                    else if(success_finding_alternative_name_but_different_groupID)
                        continue;
                    else{
                        artifactID = newArtifactID;
                        logger.info("Looking for latest version at {}...", "https://mvnrepository.com/artifact/" + groupID + "/" + artifactID);
                        doc = Jsoup.connect("https://mvnrepository.com/artifact/" + groupID + "/" + artifactID).userAgent(USER_AGENT).get();
                    }
                }

                Element latest_release = doc.selectFirst("a.vbtn.release");
                if(latest_release==null)
                {
                    logger.warn("No 'release' version found for {}", artifactID);
                    continue;
                }
                String artifactID_plus_latestRelease = latest_release.attr("href");
                String artifact_latestRelease_page = "https://mvnrepository.com/artifact/" + groupID + "/" + artifactID_plus_latestRelease;


                doc = Jsoup.connect(artifact_latestRelease_page).userAgent(USER_AGENT).get();

                Elements all_a_tags = doc.select("#maincontent table tr a");
                String jarDownloadURL = "";
                boolean success = false;
                for (Element a_tag : all_a_tags)
                    if (a_tag.text().equals("View All")) {
                        success = true;
                        jarDownloadURL = a_tag.attr("href");
                        jarDownloadURLs.add(jarDownloadURL);
                        logger.info("jarDownloadURL = {}", jarDownloadURL);
                        break;
                    }
                if (!success)
                    logger.warn("Failed to find download page for {}", artifact_page);

            } catch (Exception e) {
                logger.error("", e);
            }
        }


        // Download source.jar of each artifact
        logger.info("\n\n\n*** Downloading Jar ***");
        for(String jarDownloadURL: jarDownloadURLs) {
            Document doc = null;
            try {
                doc = Jsoup.connect(jarDownloadURL).userAgent(USER_AGENT).get();
                boolean success = false;
                for (Element aTag : doc.select("a")) {
                    if (aTag.attr("title").endsWith("-sources.jar") && !aTag.attr("title").endsWith("test-sources.jar")) {
                        success = true;
                        String downloadLink = String.format("%s/%s", jarDownloadURL, aTag.attr("href"));
                        logger.info("Downloading {}", downloadLink);
                        downloadFileAndReturnResult(downloadLink, DOWNLOAD_DIR_PATH, true);
                    }

                }
                if (!success)
                    logger.warn("No Source.jar for {}", jarDownloadURL);
            } catch (Exception e) {
                logger.error("", e);
            }
        }


        logger.info("\n\n\n*** Done ***");

    }



    public static int downloadFileAndReturnResult(String urlStr, String path, boolean override)
    {

        final int ALREADY_DOWNLOADED=1, DOWNLOADED=2, ERROR=3;

        if(path.endsWith("/"))
        {
            String filename_from_url = urlStr.substring(urlStr.lastIndexOf("/")+1);
            path = path+filename_from_url;
        }

        File resultFile = new File(path);
        if(override==false && resultFile.exists())
            return ALREADY_DOWNLOADED;
        /////////////////////////////
        int result=ERROR;
        File parentDir = resultFile.getParentFile();
        if(parentDir.exists() == false)
            parentDir.mkdirs();
        try {
            URL url = new URL(urlStr);
            URI uri = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), url.getPath(), url.getQuery(), url.getRef());
            String escaped_url = uri.toASCIIString(); // No non-ascii(示), No special characters(space), Usable for copy-past to browser
            url = new URL(escaped_url);
            FileUtils.copyURLToFile(url , resultFile);
            result = DOWNLOADED;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch(FileNotFoundException e) {
            // File not found
        } catch (IOException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return result;
    }
}
