package com.javaparser.parser.helper;

import com.anonymous.MavenUtils.ds.MavenLibInfo;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class UpdateDependencyForMissingVersions {

    public static void UpgradeMissingDependencyVersions(List<MavenLibInfo> dependencies)
    {
        for(MavenLibInfo mavenLib: dependencies)
        {
            if (Files.exists(mavenLib.GetPathToLibJar()))
                continue;

            String latestAvailableVersion = MavenLibInfo.GetLatestDownloadedVersionOfThisLibrary(mavenLib.groupId, mavenLib.artifactId);
            if(latestAvailableVersion!=null)
                mavenLib.version = latestAvailableVersion;
        }
    }
}
