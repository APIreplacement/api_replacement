package com.anon.datatype;

import java.nio.file.Path;

public class RepositoryInfo {
    private int index; // for logging purposes
    private String fullName, defaultBranch;
    private Path path; // local path
    public RepositoryInfo(String _repoFullName, String _defaultBranch)
    {
        this.fullName = _repoFullName;
        this.defaultBranch = _defaultBranch;
        this.path = null;
    }

    public void SetIndex(int _index) {
        this.index = _index;
    }

    public void SetPath(Path _path)
    {
        this.path = _path;
    }

    public String GetFullName() {
        return fullName;
    }

    public String GetName() {
        return fullName.substring(fullName.lastIndexOf('/') + 1);
    }

    public String GetOwner() {
        return fullName.substring(0, fullName.lastIndexOf('/'));
    }

    public String GetDefaultBranch() {
        return defaultBranch;
    }

    public Path GetPath() {
        return path;
    }

    @Override
    public String toString() {
        return String.format("[%s:%s #%d]", fullName, defaultBranch, index);
    }
}
