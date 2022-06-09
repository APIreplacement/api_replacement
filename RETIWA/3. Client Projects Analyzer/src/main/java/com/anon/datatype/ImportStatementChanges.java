package com.anon.datatype;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ImportStatementChanges {
    public Path filePath;
    public List<String> addedImports = new ArrayList<>();
    public List<String> removedImports = new ArrayList<>();

    public ImportStatementChanges(Path _filePath)
    {
        this.filePath = _filePath;
    }

    public void AddAAddedImport(String _importStatement)
    {
        addedImports.add(_importStatement);
    }

    public void AddARemovedImport(String _importStatement)
    {
        removedImports.add(_importStatement);
    }

    public String GetAddedImports()
    {
        StringBuilder res = new StringBuilder();
        for(int i=0; i<addedImports.size(); i++)
        {
            res.append(addedImports.get(i));
            if(i!=addedImports.size()-1)
                res.append("\n");
        }
        return res.toString();

    }

    public String GetRemovedImports()
    {
        StringBuilder res = new StringBuilder();
        for(int i=0; i<removedImports.size(); i++)
        {
            res.append(removedImports.get(i));
            if(i!=removedImports.size()-1)
                res.append("\n");
        }
        return res.toString();

    }
}
