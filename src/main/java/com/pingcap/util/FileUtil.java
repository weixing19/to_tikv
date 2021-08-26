package com.pingcap.util;

import com.pingcap.enums.Model;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class FileUtil {

    private static final Logger logger = LoggerFactory.getLogger(Model.LOG);

    public static List<File> showFileList(String filePath, boolean sort) {
        List<File> totalFileList = new ArrayList<>();
        List<File> fileList = loadDirectory(new File(filePath), totalFileList, sort);
        if (fileList == null) {
            logger.warn("Path={} has no file.", filePath);
            System.exit(0);
        } else {
            for (int i = 0; i < fileList.size(); i++) {
                logger.info("No.{}={}", i + 1, fileList.get(i).getAbsolutePath());
            }
        }
        logger.info("Total={}", fileList.size());
        return fileList;
    }

    public static List<File> loadDirectory(File fileList, List<File> totalFileList, boolean sort) {
        File[] files = fileList.listFiles();
        if (files == null) {
            logger.error("There is no file in this path {}", fileList);
            return null;
        }
        if (sort) {
            Arrays.sort(files, new ComparerByTime(""));
        }
        List<File> insideFilesList = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory()) {
                insideFilesList.add(file);
            } else {
                totalFileList.add(file);
            }
        }
        for (File file : insideFilesList) {
            loadDirectory(file, totalFileList, sort);
        }
        return totalFileList;
    }

    public static int getFileLines(File file) {
        FileReader fileReader;
        int lines = 0;
        try {
            fileReader = new FileReader(file);
            LineNumberReader lineNumberReader = new LineNumberReader(fileReader);
            long characters = lineNumberReader.skip(Long.MAX_VALUE);
            logger.debug("Skip characters={}, file={}", characters, file);
            lines = lineNumberReader.getLineNumber();
            if (!isLinux()) {
                lines++;
            }
            lineNumberReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lines;
    }

    public static HashMap<String, Long> getTtlSkipTypeMap(List<String> list) {
        HashMap<String, Long> ttlTypeCountMap = new HashMap<>(16);
        for (String ttlType : list) {
            ttlTypeCountMap.put(ttlType, 0L);
        }
        return ttlTypeCountMap;
    }

    public static synchronized void createFolder(String folderPath) {
        File checkSumFolder = new File(folderPath);
        if (checkSumFolder.exists()) {
            return;
        }
        if (!checkSumFolder.mkdir()) {
            logger.error("Failed to mkdir folder={}", folderPath);
        }
    }

    public static void deleteFolder(String folderPath) {
        File deleteFolder = new File(folderPath);
        File[] fileList = deleteFolder.listFiles();
        if (fileList == null) {
            return;
        }
        for (File file : fileList) {
            if (!file.isDirectory()) {
                if (!file.delete()) {
                    logger.error("Failed to delete file={}", file);
                }
            } else {
                deleteFolder(file.getAbsolutePath());
            }
        }
        if (!deleteFolder.delete()) {
            logger.error("Failed to delete folder={}", folderPath);
        }
    }

    public static File createFile(String filePath) {
        File file = new File(filePath);
        if (file.exists()) {
            return file;
        }
        try {
            boolean result = file.createNewFile();
            logger.debug("Result=" + result);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file;
    }

    public static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    public static LineIterator createLineIterator(File file) {
        LineIterator lineIterator = null;
        try {
            lineIterator = FileUtils.lineIterator(file, "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lineIterator;
    }

    public static void redoFile(String filePath, List<File> allFileList, Map<String, String> properties) {
        File file = new File(filePath);
        File[] files = file.listFiles();
        if (files != null) {
            List<File> fileList = new ArrayList<>();
            for (File value : files) {
                if (value.isFile()) {
                    fileList.add(value);
                } else {
                    redoFile(value.getAbsolutePath(), allFileList, properties);
                }
            }
            if (!fileList.isEmpty()) {
                File[] fileCount = new File[fileList.size()];
                for (int i = 0; i < fileList.size(); i++) {
                    fileCount[i] = fileList.get(i);
                }
                Arrays.sort(fileCount, new ComparerByTime(properties.get(Model.REDO_FILE_ORDER)));
                Collections.addAll(allFileList, fileCount);
            }
        }
    }

}

class ComparerByTime implements Comparator<File> {

    private final String order;

    // If you sort in reverse order, pass in the parameter 'desc'
    public ComparerByTime(String order) {
        this.order = order;
    }

    public int compare(File f1, File f2) {
        /*
         *  name[0]=name
         *  name[1]=log
         *  name[2]=date
         *  name[3]=num
         */
        String[] f1Name;
        String[] f2Name;
        int diff = 0;
        try {
            f1Name = f1.getName().split("\\.");
            f2Name = f2.getName().split("\\.");
            int result = 0;
            try {
                result = CountUtil.compareDate(f1Name[2], f2Name[2]);
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                if ("".equals(order)) {
                    if (result > 0) {
                        diff = 1;
                    } else if (result == 0) {
                        if (Integer.parseInt(f1Name[3]) > Integer.parseInt(f2Name[3])) {
                            diff = 1;
                        } else if (Integer.parseInt(f1Name[3]) < Integer.parseInt(f2Name[3])) {
                            diff = -1;
                        }
                    } else {
                        diff = -1;
                    }
                } else if ("desc".equals(order)) {
                    if (result > 0) {
                        diff = -1;
                    } else if (result == 0) {
                        if (Integer.parseInt(f1Name[3]) > Integer.parseInt(f2Name[3])) {
                            diff = -1;
                        } else if (Integer.parseInt(f1Name[3]) < Integer.parseInt(f2Name[3])) {
                            diff = 1;
                        }
                    } else {
                        diff = 1;
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return diff;
    }
}