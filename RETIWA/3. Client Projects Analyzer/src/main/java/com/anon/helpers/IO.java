package com.anon.helpers;

import com.opencsv.CSVWriter;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class IO {
    public static void WriteCSV_SingleColumn(List<String> data, Path writeAt){
        CSVWriter writer = null;
        try {
            writer = new CSVWriter(new FileWriter(String.valueOf(writeAt)));
            for(String row: data) {
                writer.writeNext(row.split(","), false);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(writer!=null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void WriteCSV_MultiColumn(boolean data__TO_BE_IMPLEMENTED, Path writeAt)
    {
        CSVWriter writer = null;
//        try {
//            writer = new CSVWriter(new FileWriter("path/to/file.csv"));
//            String line[] = new String[3];  //number of columns
//            for(...) {
//                line[0]="column 1 data";
//                line[1]="column 2 data";
//                line[2]="column 3 data";
//                writer.writeNext(line, false);
//            }
//        } finally {
//            if(writer!=null) writer.close();
//        }
    }


    /**
     * @param tempFilePrefix    at least three characters
     * @param tempFileSuffix    if null, .tmp will be used
     * @return  path to temporary file which `content` is written to
     */
    public static Path WriteStringOnTempFile(String content, String tempFilePrefix, String tempFileSuffix)
    {
        File file = null;
        try {
            file = File.createTempFile(tempFilePrefix, tempFileSuffix);
            FileUtils.writeStringToFile(file, content, "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Failed to create SrcML temp file");
        }
//        file.deleteOnExit();
        return file.toPath();
    }
}
