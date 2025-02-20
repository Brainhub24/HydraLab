// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.
package com.microsoft.hydralab.performance;

import com.alibaba.fastjson.JSON;
import com.microsoft.hydralab.agent.runner.ITestRun;
import com.microsoft.hydralab.agent.runner.TestRunThreadContext;
import com.microsoft.hydralab.common.entity.common.DeviceInfo;
import com.microsoft.hydralab.common.entity.common.PerformanceTestResultEntity;
import com.microsoft.hydralab.common.entity.common.TestRun;
import com.microsoft.hydralab.common.entity.common.TestTask;
import com.microsoft.hydralab.common.util.FileUtil;
import com.microsoft.hydralab.common.util.ThreadPoolUtil;
import com.microsoft.hydralab.performance.inspectors.AndroidBatteryInfoInspector;
import com.microsoft.hydralab.performance.inspectors.AndroidMemoryInfoInspector;
import com.microsoft.hydralab.performance.inspectors.IOSEnergyGaugeInspector;
import com.microsoft.hydralab.performance.inspectors.IOSMemoryPerfInspector;
import com.microsoft.hydralab.performance.inspectors.WindowsBatteryInspector;
import com.microsoft.hydralab.performance.inspectors.WindowsMemoryInspector;
import com.microsoft.hydralab.performance.parsers.AndroidBatteryInfoResultParser;
import com.microsoft.hydralab.performance.parsers.AndroidMemoryInfoResultParser;
import com.microsoft.hydralab.performance.parsers.IOSEnergyGaugeResultParser;
import com.microsoft.hydralab.performance.parsers.IOSMemoryPerfResultParser;
import com.microsoft.hydralab.performance.parsers.WindowsBatteryResultParser;
import com.microsoft.hydralab.performance.parsers.WindowsMemoryResultParser;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.springframework.util.Assert;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static com.microsoft.hydralab.performance.PerformanceInspector.PerformanceInspectorType.INSPECTOR_ANDROID_BATTERY_INFO;
import static com.microsoft.hydralab.performance.PerformanceInspector.PerformanceInspectorType.INSPECTOR_ANDROID_MEMORY_INFO;
import static com.microsoft.hydralab.performance.PerformanceInspector.PerformanceInspectorType.INSPECTOR_IOS_ENERGY;
import static com.microsoft.hydralab.performance.PerformanceInspector.PerformanceInspectorType.INSPECTOR_IOS_MEMORY;
import static com.microsoft.hydralab.performance.PerformanceInspector.PerformanceInspectorType.INSPECTOR_WIN_BATTERY;
import static com.microsoft.hydralab.performance.PerformanceInspector.PerformanceInspectorType.INSPECTOR_WIN_MEMORY;
import static com.microsoft.hydralab.performance.PerformanceResultParser.PerformanceResultParserType.PARSER_ANDROID_BATTERY_INFO;
import static com.microsoft.hydralab.performance.PerformanceResultParser.PerformanceResultParserType.PARSER_ANDROID_MEMORY_INFO;
import static com.microsoft.hydralab.performance.PerformanceResultParser.PerformanceResultParserType.PARSER_IOS_ENERGY;
import static com.microsoft.hydralab.performance.PerformanceResultParser.PerformanceResultParserType.PARSER_IOS_MEMORY;
import static com.microsoft.hydralab.performance.PerformanceResultParser.PerformanceResultParserType.PARSER_WIN_BATTERY;
import static com.microsoft.hydralab.performance.PerformanceResultParser.PerformanceResultParserType.PARSER_WIN_MEMORY;

public class PerformanceTestManagementService implements IPerformanceInspectionService, PerformanceTestListener {
    private static final Map<PerformanceInspector.PerformanceInspectorType, PerformanceResultParser.PerformanceResultParserType> inspectorParserTypeMap = Map.of(
            INSPECTOR_ANDROID_BATTERY_INFO, PARSER_ANDROID_BATTERY_INFO,
            INSPECTOR_ANDROID_MEMORY_INFO, PARSER_ANDROID_MEMORY_INFO,
            INSPECTOR_WIN_MEMORY, PARSER_WIN_MEMORY,
            INSPECTOR_WIN_BATTERY, PARSER_WIN_BATTERY,
            INSPECTOR_IOS_ENERGY, PARSER_IOS_ENERGY,
            INSPECTOR_IOS_MEMORY, PARSER_IOS_MEMORY
    );
    private final Map<PerformanceInspector.PerformanceInspectorType, PerformanceInspector> performanceInspectorMap = Map.of(
            INSPECTOR_ANDROID_BATTERY_INFO, new AndroidBatteryInfoInspector(),
            INSPECTOR_ANDROID_MEMORY_INFO, new AndroidMemoryInfoInspector(),
            INSPECTOR_WIN_MEMORY, new WindowsMemoryInspector(),
            INSPECTOR_WIN_BATTERY, new WindowsBatteryInspector(),
            INSPECTOR_IOS_MEMORY, new IOSMemoryPerfInspector(),
            INSPECTOR_IOS_ENERGY, new IOSEnergyGaugeInspector()
    );
    private final Map<PerformanceResultParser.PerformanceResultParserType, PerformanceResultParser> performanceResultParserMap = Map.of(
            PARSER_ANDROID_MEMORY_INFO, new AndroidMemoryInfoResultParser(),
            PARSER_ANDROID_BATTERY_INFO, new AndroidBatteryInfoResultParser(),
            PARSER_WIN_MEMORY, new WindowsMemoryResultParser(),
            PARSER_WIN_BATTERY, new WindowsBatteryResultParser(),
            PARSER_IOS_ENERGY, new IOSEnergyGaugeResultParser(),
            PARSER_IOS_MEMORY, new IOSMemoryPerfResultParser()
    );

    private final Map<String, List<ScheduledFuture<?>>> inspectPerformanceTimerMap = new ConcurrentHashMap<>();
    private final Map<String, List<InspectionStrategy>> testLifeCycleStrategyMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PerformanceTestResult>> testRunPerfResultMap = new ConcurrentHashMap<>();

    public void initialize() {
        PerformanceInspectionService.getInstance().swapImplementation(this);
    }

    @NotNull
    private static PerformanceTestResult createPerformanceTestResult(PerformanceInspection performanceInspection) {
        PerformanceTestResult performanceTestResult = new PerformanceTestResult();
        performanceTestResult.inspectorType = performanceInspection.inspectorType;
        performanceTestResult.parserType = getParserTypeByInspection(performanceInspection);
        return performanceTestResult;
    }

    private static PerformanceResultParser.PerformanceResultParserType getParserTypeByInspection(PerformanceInspection performanceInspection) {
        return inspectorParserTypeMap.get(performanceInspection.inspectorType);
    }

    private PerformanceInspector getInspectorByType(PerformanceInspector.PerformanceInspectorType inspectorType) {
        return performanceInspectorMap.get(inspectorType);
    }

    private PerformanceResultParser getParserByType(PerformanceResultParser.PerformanceResultParserType parserType) {
        return performanceResultParserMap.get(parserType);
    }

    @Override
    public PerformanceInspectionResult inspect(PerformanceInspection performanceInspection) {
        ITestRun testRun = getTestRun();
        return inspect(performanceInspection, testRun);
    }

    private PerformanceInspectionResult inspect(PerformanceInspection performanceInspection, ITestRun testRun) {
        if (performanceInspection == null || testRun == null) return null;

        performanceInspection = getDevicePerformanceInspection(performanceInspection);
        PerformanceInspector.PerformanceInspectorType inspectorType = performanceInspection.inspectorType;
        PerformanceInspector performanceInspector = getInspectorByType(inspectorType);
        Assert.notNull(performanceInspector, "Found no matched inspector: " + performanceInspection.inspectorType);
        File performanceFolder = new File(testRun.getResultFolder(), PerformanceInspection.class.getSimpleName());
        Assert.isTrue(performanceFolder.exists() || performanceFolder.mkdirs(), "performanceInspection.resultFolder.mkdirs() failed in " + performanceFolder.getAbsolutePath());
        File inspectorFolder = new File(performanceFolder, inspectorType.name());
        Assert.isTrue(inspectorFolder.exists() || inspectorFolder.mkdirs(), "performanceInspection.resultFolder.mkdirs() failed in " + inspectorFolder.getAbsolutePath());
        performanceInspection.resultFolder = inspectorFolder;

        PerformanceInspectionResult result = performanceInspector.inspect(performanceInspection);

        testRunPerfResultMap.putIfAbsent(testRun.getId(), new HashMap<>());
        Map<String, PerformanceTestResult> performanceTestResultMap = testRunPerfResultMap.get(testRun.getId());
        Assert.notNull(performanceTestResultMap, "performanceTestResultMap should not be null ");
        performanceTestResultMap.putIfAbsent(performanceInspection.inspectionKey, createPerformanceTestResult(performanceInspection));
        PerformanceTestResult performanceTestResult = performanceTestResultMap.get(performanceInspection.inspectionKey);
        Assert.notNull(performanceTestResult, "performanceTestResult should not be null ");
        performanceTestResult.performanceInspectionResults.add(result);

        return result;
    }

    /**
     * @return the test run object from TestRunThreadContext
     */
    private ITestRun getTestRun() {
        return TestRunThreadContext.getTestRun();
    }

    public void inspectWithStrategy(InspectionStrategy inspectionStrategy) {
        if (inspectionStrategy == null || inspectionStrategy.inspection == null) return;

        ITestRun testRun = getTestRun();
        if (inspectionStrategy.strategyType == InspectionStrategy.StrategyType.TEST_SCHEDULE) {
            PerformanceInspection inspection = inspectionStrategy.inspection;
            /* initialize inspector */
            PerformanceInspection initialInspection = new PerformanceInspection(InspectionStrategy.WhenType.TEST_RUN_STARTED.name(),
                    inspection.inspectorType, inspection.appId, inspection.deviceIdentifier, true);
            inspect(initialInspection);

            ScheduledFuture<?> scheduledFuture = ThreadPoolUtil.PERFORMANCE_TEST_TIMER_EXECUTOR.scheduleAtFixedRate(() -> {
                inspect(inspection, testRun);
            }, 0, inspectionStrategy.interval, inspectionStrategy.intervalUnit);
            inspectPerformanceTimerMap.putIfAbsent(testRun.getId(), new ArrayList<>());
            inspectPerformanceTimerMap.get(testRun.getId()).add(scheduledFuture);
        }
        if (inspectionStrategy.strategyType == InspectionStrategy.StrategyType.TEST_LIFECYCLE) {
            if (inspectionStrategy.when == null || inspectionStrategy.when.isEmpty()) {
                //Inspect whole lifecycle by default if when is not specified
                inspectionStrategy.when = Arrays.asList(InspectionStrategy.WhenType.values());
            }

            testLifeCycleStrategyMap.putIfAbsent(testRun.getId(), new ArrayList<>());
            testLifeCycleStrategyMap.get(testRun.getId()).add(inspectionStrategy);
        }
    }

    @Override
    public PerformanceTestResult parse(PerformanceInspection performanceInspection) {
        if (performanceInspection == null) return null;

        performanceInspection = getDevicePerformanceInspection(performanceInspection);
        Map<String, PerformanceTestResult> testResultMap = testRunPerfResultMap.get(getTestRun().getId());
        Assert.notNull(testResultMap, "Found no matched test result for test run");
        PerformanceTestResult performanceTestResult = testResultMap.get(performanceInspection.inspectionKey);
        Assert.notNull(performanceTestResult, "Found no matched performanceTestResult for performanceInspectionKey: " + performanceInspection.inspectionKey);
        PerformanceResultParser parser = getParserByType(performanceTestResult.parserType);
        Assert.notNull(parser, "Found no matched result parser: " + performanceTestResult.parserType);
        return parser.parse(performanceTestResult);
    }

    @Override
    public void testRunStarted() {
        inspectWithLifeCycle(InspectionStrategy.WhenType.TEST_RUN_STARTED, "start_run");
    }

    @Override
    public void testRunFinished() {
        inspectWithLifeCycle(InspectionStrategy.WhenType.TEST_RUN_FINISHED, "stop_run");
    }

    @Override
    public void testStarted(String description) {
        inspectWithLifeCycle(InspectionStrategy.WhenType.TEST_STARTED, description);
    }

    @Override
    public void testSuccess(String description) {
        inspectWithLifeCycle(InspectionStrategy.WhenType.TEST_SUCCESS, description);
    }

    @Override
    public void testFailure(String description) {
        inspectWithLifeCycle(InspectionStrategy.WhenType.TEST_FAILURE, description);
    }

    public void testTearDown(DeviceInfo deviceInfo, TestTask testTask, TestRun testRun, Logger log) {
        List<ScheduledFuture<?>> timerList = inspectPerformanceTimerMap.get(testRun.getId());
        if (timerList != null) {
            for (ScheduledFuture<?> timer : timerList) {
                timer.cancel(true);
            }
        }
        List<PerformanceTestResult> resultList = parseForTestRun(testRun);
        savePerformanceTestResults(resultList, testRun, testTask, log);

        inspectPerformanceTimerMap.remove(testRun.getId());
        testLifeCycleStrategyMap.remove(testRun.getId());
        testRunPerfResultMap.remove(testRun.getId());

        //TODO Android battery: adb shell dumpsys battery reset using Device info
    }

    private void inspectWithLifeCycle(InspectionStrategy.WhenType whenType, String description) {
        List<InspectionStrategy> strategyList = testLifeCycleStrategyMap.get(getTestRun().getId());
        if (strategyList == null) return;

        for (InspectionStrategy inspectionStrategy : strategyList) {
            if (inspectionStrategy == null) continue;
            PerformanceInspection inspection = inspectionStrategy.inspection;
            if (inspectionStrategy.when == null) continue;

            if (inspectionStrategy.when.contains(whenType) || whenType == InspectionStrategy.WhenType.TEST_RUN_STARTED) {
                PerformanceInspection lifeCycleInspection = new PerformanceInspection(
                        whenType.name() + "-" + description, inspection.inspectorType, inspection.appId,
                        inspection.deviceIdentifier, whenType == InspectionStrategy.WhenType.TEST_RUN_STARTED);
                inspect(lifeCycleInspection);
            }
        }
    }

    /**
     * For giving inspection return the inspection with device id that related to test run
     */
    private PerformanceInspection getDevicePerformanceInspection(PerformanceInspection inspection) {
        return new PerformanceInspection(inspection.description, inspection.inspectorType, inspection.appId,
                // For windows inspector, the deviceIdentifier is useless
                getTestRun().getDeviceSerialNumber(), inspection.isReset);
    }

    private List<PerformanceTestResult> parseForTestRun(ITestRun testRun) {
        Map<String, PerformanceTestResult> testResultMap = testRunPerfResultMap.get(testRun.getId());
        if (testResultMap == null) return null;

        List<PerformanceTestResult> resultList = new ArrayList<>();
        for (PerformanceTestResult performanceTestResult : testResultMap.values()) {
            PerformanceResultParser parser = getParserByType(performanceTestResult.parserType);
            Assert.notNull(parser, "Found no matched result parser: " + performanceTestResult.parserType);
            resultList.add(parser.parse(performanceTestResult));
        }
        return resultList;
    }

    private void savePerformanceTestResults(List<PerformanceTestResult> resultList, TestRun testRun, TestTask testTask, Logger log) {
        if (resultList != null && !resultList.isEmpty()) {
            try {
                FileUtil.writeToFile(JSON.toJSONString(resultList),
                        getTestRun().getResultFolder() + File.separator + "PerformanceReport.json");

                for (PerformanceTestResult testResult : resultList) {
                    if (testResult.getResultSummary() == null
                            || testResult.performanceInspectionResults == null
                            || testResult.performanceInspectionResults.isEmpty()
                            || testResult.performanceInspectionResults.get(0).inspection == null) {
                        continue;
                    }

                    PerformanceInspection inspection = testResult.performanceInspectionResults.get(0).inspection;
                    PerformanceTestResultEntity testResultEntity = new PerformanceTestResultEntity(
                            testRun.getId(),
                            testTask.getId(),
                            testResult.inspectorType,
                            testResult.parserType,
                            testResult.getResultSummary(),
                            testTask.getTestSuite(),
                            testTask.getRunningType(),
                            inspection.appId,
                            inspection.deviceIdentifier,
                            testRun.isSuccess());
                    testRun.getPerformanceTestResultEntities().add(testResultEntity);
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                log.error("Failed to save performance test results", e);
            }
        }
    }


}
