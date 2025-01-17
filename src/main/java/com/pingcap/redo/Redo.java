package com.pingcap.redo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.pingcap.enums.Model;
import com.pingcap.pojo.IndexInfo;
import com.pingcap.pojo.TempIndexInfo;
import com.pingcap.util.CountUtil;
import com.pingcap.util.FileUtil;
import com.pingcap.util.PropertiesUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.TiSession;
import org.tikv.raw.RawKVClient;
import org.tikv.shade.com.google.protobuf.ByteString;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.pingcap.enums.Model.*;

public class Redo {

    private static final Logger logger = LoggerFactory.getLogger(Model.LOG);
    private static final Logger redoLog = LoggerFactory.getLogger(Model.REDO_LOG);
    private static final Logger redoFailLog = LoggerFactory.getLogger(REDO_FAIL_LOG);

    public static void run(Map<String, String> properties, TiSession tiSession) {

        long startTime = System.currentTimeMillis();

        // Redo file
        PropertiesUtil.checkConfig(properties, REDO_FILE_PATH);
        String redoFilePath = properties.get(Model.REDO_FILE_PATH);

        // Redo.log move path
        PropertiesUtil.checkConfig(properties, REDO_MOVE_PATH);
        String moveFilePath = properties.get(Model.REDO_MOVE_PATH);

        // Redo data type, indexInfo or tempIndexInfo
        PropertiesUtil.checkConfig(properties, REDO_TYPE);
        String type = properties.get(Model.REDO_TYPE);

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        String now = simpleDateFormat.format(new Date());

        // MoveFilePath
        FileUtil.createFolder(moveFilePath);
        FileUtil.createFolder(moveFilePath + "/" + now);

        PropertiesUtil.checkConfig(properties, KEY_DELIMITER);
        String keyDelimiter = properties.get(KEY_DELIMITER);

        // Redo failed log
//        String redoFailPath = moveFilePath + "/" + now + "/" + "redoFail.txt";
//        File redoFailFile = FileUtil.createFile(redoFailPath);
//        FileOutputStream fileOutputStream;
//        FileChannel redoFileFileChannel = null;
//        try {
//            fileOutputStream = new FileOutputStream(redoFailFile);
//            redoFileFileChannel = fileOutputStream.getChannel();
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        }

        // Manual confirmation log
//        String redoNotExistsPath = moveFilePath + "/" + now + "/" + "manual_confirmation.txt";
//        File redoNotExistsFile = FileUtil.createFile(redoNotExistsPath);
//        FileOutputStream fileOutputStream1;
//        FileChannel redoNotExistsFileChannel = null;
//        try {
//            fileOutputStream1 = new FileOutputStream(redoNotExistsFile);
//            redoNotExistsFileChannel = fileOutputStream1.getChannel();
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        }

        RedoSummary redoSummary;
        JSONObject jsonObject;
        LineIterator lineIterator;
        String redoLine, k, envId;
        ByteString vv;
        RawKVClient rawKVClient = tiSession.createRawClient();
        IndexInfo indexInfoRedo, indexInfoRedoValue, indexInfoTiKVValue;
        TempIndexInfo tempIndexInfoRedo, tempIndexInfoRedoValue;

//        if (redoFileFileChannel != null) {

        List<File> redoFileList = new ArrayList<>();
        FileUtil.redoFile(redoFilePath, redoFileList, properties);
        int n = 0;
        for (File redoFile : redoFileList) {

            logger.info("start redo file={}", redoFile);

            redoSummary = new RedoSummary();
            try {
                lineIterator = FileUtils.lineIterator(redoFile);
                int totalCount = 0;

                /*
                 * Redo
                 * 1.indexInfo
                 *   1) add:
                 *      v == null
                 *          a. redo
                 *      v != null
                 *          a. Raw KV > redo, not redo.
                 *          b. Raw KV < redo, do  redo.
                 *   2) update:
                 *      v == null
                 *          a. redo
                 *      v != null
                 *          a. Raw KV > redo, not redo.
                 *          b. Raw KV < redo, do  redo.
                 *   3) delete:
                 *      v == null
                 *          a. skip
                 *      v != null
                 *          a. Raw KV > redo, not redo.
                 *          b. Raw kv < redo, do  redo.
                 *  2.tempIndexInfo
                 *    No comparison, direct coverage.
                 */
                while (lineIterator.hasNext()) {
                    totalCount++;
                    redoLine = lineIterator.nextLine();
                    if (StringUtils.isBlank(redoLine) || "".equals(redoLine.trim())) {
                        redoLog.warn("There is blank lines in the file={}, line={}", redoFile.getAbsolutePath(), totalCount);
                        redoSummary.setSkip(redoSummary.getSkip() + 1);
                        continue;
                    }
                    try {
                        // Parse
                        jsonObject = JSONObject.parseObject(redoLine);
                    } catch (Exception e) {
                        redoLog.error("Parse failed, file={}, data={}, line={}", redoFile, redoLine, totalCount);
                        redoSummary.setParseErr(redoSummary.getParseErr() + 1);
                        continue;
                    }
                    switch (type) {
                        case Model.INDEX_INFO:
                            indexInfoRedo = JSON.toJavaObject(jsonObject, IndexInfo.class);
                            // If envId exists, overwrite
//                            if (!StringUtils.isEmpty(properties.get(ENV_ID))) {
//                                envId = properties.get(ENV_ID);
//                            } else {
                            envId = indexInfoRedo.getEnvId();
//                            }
                            // key
                            k = String.format(IndexInfo.KET_FORMAT, keyDelimiter, envId, keyDelimiter, indexInfoRedo.getType(), keyDelimiter, indexInfoRedo.getId());
                            try {
                                vv = rawKVClient.get(ByteString.copyFromUtf8(k));
                            } catch (Exception e) {
                                redoLog.error("Get failed, key={}, file={}, line={}", k, redoFile.getAbsolutePath(), totalCount, e);
                                redoFailLog.info(redoLine);
                                redoSummary.setGetErr(redoSummary.getGetErr() + 1);
                                continue;
                            }
                            if (indexInfoRedo.getOpType() == null) {
                                redoLog.error("Redo data opType must not be null. File={}, data={}, line={}", redoFile, redoLine, totalCount);
                                System.exit(0);
                            }
                            switch (indexInfoRedo.getOpType()) {
                                case ADD:
                                case UPDATE:
                                    if (!vv.isEmpty()) {
                                        indexInfoTiKVValue = JSON.parseObject(vv.toStringUtf8(), IndexInfo.class);
                                        if (indexInfoRedo.getUpdateTime() != null && indexInfoTiKVValue.getUpdateTime() != null) {
                                            // Redo > RawKV
                                            if (CountUtil.compareTime(indexInfoRedo.getUpdateTime(), indexInfoTiKVValue.getUpdateTime()) >= 0) {
                                                indexInfoRedoValue = new IndexInfo();
                                                IndexInfo.initValueIndexInfoTiKV(indexInfoRedoValue, indexInfoRedo);
                                                // if duration == 0, D
                                                put(rawKVClient, k, JSON.toJSONString(indexInfoRedoValue), redoSummary, Long.parseLong(indexInfoRedo.getDuration()), redoLine, totalCount);
                                            } else {
                                                redoLog.info("Key={}, redoTso={} < rawkvTso={}, line = {}, so skip. OpType={}.", k, indexInfoRedo.getUpdateTime(), indexInfoTiKVValue.getUpdateTime(), totalCount, indexInfoRedo.getOpType());
                                                redoSummary.setSkip(redoSummary.getSkip() + 1);
                                            }
                                        } else {
                                            // If timeStamp is null, put
                                            indexInfoRedoValue = new IndexInfo();
                                            IndexInfo.initValueIndexInfoTiKV(indexInfoRedoValue, indexInfoRedo);
                                            put(rawKVClient, k, JSON.toJSONString(indexInfoRedoValue), redoSummary, Long.parseLong(indexInfoRedo.getDuration()), redoLine, totalCount);
                                        }
                                    } else {
                                        // If raw kv is not exists, put.
                                        indexInfoRedoValue = new IndexInfo();
                                        IndexInfo.initValueIndexInfoTiKV(indexInfoRedoValue, indexInfoRedo);
                                        put(rawKVClient, k, JSON.toJSONString(indexInfoRedoValue), redoSummary, Long.parseLong(indexInfoRedo.getDuration()), redoLine, totalCount);
                                    }
                                    break;
                                case DELETE:
                                    if (!vv.isEmpty()) {
                                        indexInfoTiKVValue = JSON.parseObject(vv.toStringUtf8(), IndexInfo.class);
                                        if (indexInfoRedo.getUpdateTime() != null && indexInfoTiKVValue.getUpdateTime() != null) {
                                            if (CountUtil.compareTime(indexInfoRedo.getUpdateTime(), indexInfoTiKVValue.getUpdateTime()) >= 0) {
                                                delete(rawKVClient, k, redoSummary, redoLine, totalCount);
                                            } else {
                                                redoLog.info("Key={}, redoTso={} < rawkvTso={}, line = {}, so skip. OpType={}", k, indexInfoRedo.getUpdateTime(), indexInfoTiKVValue.getUpdateTime(), totalCount, indexInfoRedo.getOpType());
                                                redoSummary.setSkip(redoSummary.getSkip() + 1);
                                            }
                                        } else {
                                            delete(rawKVClient, k, redoSummary, redoLine, totalCount);
                                        }
                                    } else {
                                        redoLog.info("Key={}, raw KV not exists, line = {}, so skip. OpType={}", k, totalCount, DELETE);
                                        redoSummary.setSkip(redoSummary.getSkip() + 1);
                                    }
                                    break;
                                default:
                                    throw new IllegalStateException(indexInfoRedo.getOpType());
                            }
                            break;
                        case Model.TEMP_INDEX_INFO:
                            tempIndexInfoRedo = JSON.toJavaObject(jsonObject, TempIndexInfo.class);
                            tempIndexInfoRedoValue = new TempIndexInfo();
                            k = String.format(TempIndexInfo.KEY_FORMAT, keyDelimiter, tempIndexInfoRedo.getEnvId(), keyDelimiter, tempIndexInfoRedo.getId());
                            if (tempIndexInfoRedo.getOpType() == null) {
                                redoLog.error("Redo data opType must not be null. File={}, data={}, line={}", redoFile, redoLine, totalCount);
                                System.exit(0);
                            }
                            switch (tempIndexInfoRedo.getOpType()) {
                                case ADD:
                                case UPDATE:
                                    TempIndexInfo.initValueTempIndexInfo(tempIndexInfoRedoValue, tempIndexInfoRedo);
                                    put(rawKVClient, k, JSON.toJSONString(tempIndexInfoRedoValue), redoSummary, 604800, redoLine, totalCount);
                                    break;
                                case DELETE:
                                    delete(rawKVClient, k, redoSummary, redoLine, totalCount);
                                    break;
                                default:
                                    throw new IllegalStateException(tempIndexInfoRedo.getOpType());
                            }
                            break;
                        default:
                            redoLog.error("Redo error type={}", type);
                            System.exit(0);
                    }

                }

                redoLog.info("Redo file={} complete. " +
                                "total={}, " +
                                "redoPut={}, " +
                                "redoDelete={}, " +
                                "redoPutErr={}, " +
                                "redoDeleteErr={}, " +
                                "getErr={}, " +
                                "parseErr={}, " +
                                "skip={}, " +
                                "notSure={}",
                        redoFile.getAbsolutePath(),
                        totalCount,
                        redoSummary.getRedo(),
                        redoSummary.getDelete(),
                        redoSummary.getPutErr(),
                        redoSummary.getDeleteErr(),
                        redoSummary.getGetErr(),
                        redoSummary.getParseErr(),
                        redoSummary.getSkip(),
                        redoSummary.getNotSure());

                File moveFile = new File(moveFilePath + "/" + now + "/" + redoFile.getName() + "." + n++);
                FileUtils.moveFile(redoFile, moveFile);

                logger.info("Redo file={} complete.", redoFile);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        rawKVClient.close();
        redoLog.info("Redo all complete, duration={}s", (System.currentTimeMillis() - startTime) / 1000);
//        } else {
//            redoLog.error("Redo failed.");
//        }

    }

    public static void put(RawKVClient rawKVClient, String key, String value, RedoSummary redoSummary, long ttl, String fileLine, int lineNum) {
        try {
            rawKVClient.put(ByteString.copyFromUtf8(key), ByteString.copyFromUtf8(value), ttl);
        } catch (Exception e) {
            redoLog.error("Redo put failed. key={}, line={}, lineNum={}", key, fileLine, lineNum, e);
            redoFailLog.info(fileLine);
            redoSummary.setPutErr(redoSummary.getPutErr() + 1);
            return;
        }
        redoSummary.setRedo(redoSummary.getRedo() + 1);
    }

    public static void delete(RawKVClient rawKVClient, String k, RedoSummary redoSummary, String fileLine, int lineNum) {
        try {
            rawKVClient.delete(ByteString.copyFromUtf8(k));
        } catch (Exception e) {
            redoLog.error("Redo delete failed. key={}, line={}, lineNum={}", k, fileLine, lineNum, e);
            redoFailLog.info(fileLine);
            redoSummary.setDeleteErr(redoSummary.getDeleteErr() + 1);
            return;
        }
        redoSummary.setDelete(redoSummary.getDelete() + 1);
    }

    public static void write(String redoLine, FileChannel fileChannel, RedoSummary redoSummary) {
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(redoLine + "\n");
        try {
            fileChannel.write(byteBuffer);
        } catch (IOException e) {
            e.printStackTrace();
        }
        redoSummary.setNotSure(redoSummary.getNotSure() + 1);
    }

}

class RedoSummary {

    private int redo = 0;
    private int parseErr = 0;
    private int delete = 0;
    private int skip = 0;
    private int putErr = 0;
    private int deleteErr = 0;
    private int notSure = 0;
    private int getErr = 0;

    public int getRedo() {
        return redo;
    }

    public void setRedo(int redo) {
        this.redo = redo;
    }

    public int getParseErr() {
        return parseErr;
    }

    public void setParseErr(int parseErr) {
        this.parseErr = parseErr;
    }

    public int getDelete() {
        return delete;
    }

    public void setDelete(int delete) {
        this.delete = delete;
    }

    public int getSkip() {
        return skip;
    }

    public void setSkip(int skip) {
        this.skip = skip;
    }

    public int getPutErr() {
        return putErr;
    }

    public void setPutErr(int putErr) {
        this.putErr = putErr;
    }

    public int getDeleteErr() {
        return deleteErr;
    }

    public void setDeleteErr(int deleteErr) {
        this.deleteErr = deleteErr;
    }

    public int getNotSure() {
        return notSure;
    }

    public void setNotSure(int notSure) {
        this.notSure = notSure;
    }

    public int getGetErr() {
        return getErr;
    }

    public void setGetErr(int getErr) {
        this.getErr = getErr;
    }
}

