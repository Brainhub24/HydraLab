package com.microsoft.hydralab.performance.inspectors;

import com.microsoft.hydralab.common.util.ShellUtils;
import com.microsoft.hydralab.common.util.TimeUtils;
import com.microsoft.hydralab.performance.PerformanceInspection;
import com.microsoft.hydralab.performance.PerformanceInspectionResult;
import com.microsoft.hydralab.performance.PerformanceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.File;

public class AndroidMemoryInfoInspector implements PerformanceInspector  {

    private static final String RAW_RESULT_FILE_NAME_FORMAT = "%s_%s_%s_memory.txt";
    private final Logger classLogger = LoggerFactory.getLogger(getClass());


    @Override
    public PerformanceInspectionResult inspect(PerformanceInspection performanceInspection) {

        File rawResultFolder = new File(performanceInspection.resultFolder, performanceInspection.appId);
        Assert.isTrue(rawResultFolder.exists() || rawResultFolder.mkdir(), "rawResultFolder.mkdirs() failed in" + rawResultFolder.getAbsolutePath());
        File rawResultFile = new File(rawResultFolder,
                String.format(RAW_RESULT_FILE_NAME_FORMAT, getClass().getSimpleName(), performanceInspection.appId, TimeUtils.getTimestampForFilename()));

        ShellUtils.execLocalCommandWithResult(String.format(getMemInfoCommand(),
                performanceInspection.deviceIdentifier, performanceInspection.appId, rawResultFile.getAbsolutePath()), classLogger);
        return new PerformanceInspectionResult(rawResultFile, performanceInspection);
    }

    private String getMemInfoCommand() {
        String format;
        if (ShellUtils.isConnectedToWindowsOS) {
            format = "adb -s %s shell dumpsys meminfo %s | out-file %s -encoding utf8";
        } else {
            format = "adb -s %s shell dumpsys meminfo %s > %s";
        }
        return format;
    }
}
