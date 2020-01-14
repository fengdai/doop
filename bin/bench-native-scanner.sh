#!/usr/bin/env bash

CURRENT_DIR="$(pwd)"
# echo "CURRENT_DIR=${CURRENT_DIR}"

if [ "${DOOP_HOME}" == "" ]; then
    echo "Please set DOOP_HOME."
    exit
fi
if [ "${DOOP_BENCHMARKS}" == "" ]; then
    echo "Please set DOOP_BENCHMARKS."
    exit
fi
if [ "${SERVER_ANALYSIS_TESTS}" == "" ]; then
    echo "Please set SERVER_ANALYSIS_TESTS."
    exit
fi
if [ "${XCORPUS_DIR}" == "" ]; then
    echo "Please set XCORPUS_DIR."
    exit
fi

ANALYSIS=context-insensitive
TIMEOUT=600
STRING_DISTANCE1=40
STRING_DISTANCE2=2000

if [ "$1" == "report" ]; then
    RUN_ANALYSIS=0
elif [ "$1" == "analyze" ]; then
    RUN_ANALYSIS=1
else
    echo "Usage: bench-native-scanner.sh [analyze|report]"
    exit
fi

# The "fulljars" platform is the full Android code, while "stubs"
# should only be used for recall calculation, not reachability metrics.
ANDROID_PLATFORM=android_25_fulljars
# ANDROID_PLATFORM=android_25_stubs

JVM_NATIVE_CODE=${DOOP_HOME}/jvm8-native-code.jar

function setIntersection() {
    comm -1 -2 <(sort -u "$1") <(sort -u "$2")
}

function setDifference() {
    comm -2 -3 <(sort -u "$1") <(sort -u "$2")
}

function calcIncrease() {
    local ARG1=$(echo "$1" | sed -e 's/,/./g')
    local ARG2=$(echo "$2" | sed -e 's/,/./g')
    python -c "print('%.2f' % (100.0 * (${ARG2} - ${ARG1}) / ${ARG1}) + '%')"
}

function printStatsRow() {
    local BENCHMARK="$1"
    local ID_BASE="$2"
    local ID_SCANNER="$3"
    local ID_DYNAMIC="$4"
    local MODE="$5"

    # local DYNAMIC_METHODS=${DOOP_HOME}/out/${ANALYSIS}/${ID_DYNAMIC}/database/mainAnalysis.DynamicAppCallGraphEdgeFromNative.csv
    local DYNAMIC_METHODS=${DOOP_HOME}/out/${ANALYSIS}/${ID_DYNAMIC}/database/mainAnalysis.DynamicAppNativeCodeTarget.csv
    # local SCANNER_METHODS=${DOOP_HOME}/out/${ANALYSIS}/${ID_SCANNER}/database/basic.AppCallGraphEdgeFromNativeMethod.csv
    local SCANNER_METHODS=${DOOP_HOME}/out/${ANALYSIS}/${ID_SCANNER}/database/Stats_Simple_Application_ReachableMethod.csv
    local MISSED_FILE="${CURRENT_DIR}/missed-methods-${ID_SCANNER}.log"

    if [ ! -f "${SCANNER_METHODS}" ]; then
        return
    fi

    # echo "== Benchmark: ${BENCHMARK} (static scanner mode: ${ID_SCANNER}) =="
    # echo "Intersection file: ${INTERSECTION_FILE}"
    # echo "Dynamic methods: ${DYNAMIC_METHODS}"
    # echo "Scanner methods: ${SCANNER_METHODS}"
    # echo "Missed methods: ${MISSED_FILE}"

    local BASE_INTERSECTION_FILE="${CURRENT_DIR}/dynamic-scanner-intersection-${ID_BASE}.log"
    local BASE_APP_REACHABLE_FILE=${DOOP_HOME}/out/${ANALYSIS}/${ID_BASE}/database/Stats_Simple_Application_ReachableMethod.csv
    local BASE_APP_REACHABLE=$(cat ${BASE_APP_REACHABLE_FILE} | wc -l)
    local SCANNER_APP_REACHABLE=$(cat ${SCANNER_METHODS} | wc -l)

    if [ -f "${DYNAMIC_METHODS}" ]; then
        local DYNAMIC_METHODS_COUNT=$(cat ${DYNAMIC_METHODS} | wc -l)
        # 1. Calculate recall of the "base" analysis.
        setIntersection ${DYNAMIC_METHODS} ${BASE_APP_REACHABLE_FILE} > ${BASE_INTERSECTION_FILE}
        local BASE_INTERSECTION_COUNT=$(cat ${BASE_INTERSECTION_FILE} | wc -l)
        local BASE_RECALL="${BASE_INTERSECTION_COUNT}/${DYNAMIC_METHODS_COUNT} = "$(python -c "print('%.2f' % (100.0 * ${BASE_INTERSECTION_COUNT} / ${DYNAMIC_METHODS_COUNT}) + '%')")
        # 2. Calculate recall of the "scanner" analysis.
        local INTERSECTION_FILE="${CURRENT_DIR}/dynamic-scanner-intersection-${ID_SCANNER}.log"
        setIntersection ${DYNAMIC_METHODS} ${SCANNER_METHODS} > ${INTERSECTION_FILE}
        local INTERSECTION_COUNT=$(cat ${INTERSECTION_FILE} | wc -l)
        local RECALL="${INTERSECTION_COUNT}/${DYNAMIC_METHODS_COUNT} = "$(python -c "print('%.2f' % (100.0 * ${INTERSECTION_COUNT} / ${DYNAMIC_METHODS_COUNT}) + '%')")

        comm -2 -3 <(sort -u ${DYNAMIC_METHODS}) <(sort -u ${SCANNER_METHODS}) > ${MISSED_FILE}
    fi

    local APP_METHOD_COUNT=$(cat ${DOOP_HOME}/out/${ANALYSIS}/${ID_BASE}/database/ApplicationMethod.csv | wc -l)
    # echo "Application methods: ${APP_METHOD_COUNT}"
    local APP_REACHABLE_DELTA="(${BASE_APP_REACHABLE} -> ${SCANNER_APP_REACHABLE}): "$(calcIncrease ${BASE_APP_REACHABLE} ${SCANNER_APP_REACHABLE})
    # echo "App-reachable increase over base: ${APP_REACHABLE_DELTA}"

    # Use 'xargs' to remove whitespace.
    local BASE_ANALYSIS_TIME=$(grep -F 'analysis execution time (sec)' ${CURRENT_DIR}/${ID_BASE}.log | cut -d ')' -f 2 | xargs)
    local SCANNER_ANALYSIS_TIME=$(grep -F 'analysis execution time (sec)' ${CURRENT_DIR}/${ID_SCANNER}.log | cut -d ')' -f 2 | xargs)
    local BASE_FACTS_TIME=$(grep -F 'Soot fact generation time:' ${CURRENT_DIR}/${ID_BASE}.log | cut -d ':' -f 2 | xargs)
    local SCANNER_FACTS_TIME=$(grep -F 'Soot fact generation time:' ${CURRENT_DIR}/${ID_SCANNER}.log | cut -d ':' -f 2 | xargs)
    local ANALYSIS_TIME_DELTA="(${BASE_ANALYSIS_TIME} -> ${SCANNER_ANALYSIS_TIME}): "$(calcIncrease ${BASE_ANALYSIS_TIME} ${SCANNER_ANALYSIS_TIME})
    local FACTS_TIME_DELTA="(${BASE_FACTS_TIME} -> ${SCANNER_FACTS_TIME}): "$(calcIncrease ${BASE_FACTS_TIME} ${SCANNER_FACTS_TIME})
    # echo "Analysis time increase over base: ${ANALYSIS_TIME_DELTA}"

    local SCANNER_ENTRY_POINTS=${DOOP_HOME}/out/${ANALYSIS}/${ID_SCANNER}/database/mainAnalysis.ReachableAppMethodFromNativeCode.csv
    local ADDED_ENTRY_POINTS=$(setDifference ${SCANNER_ENTRY_POINTS} ${BASE_APP_REACHABLE_FILE} | wc -l)

    echo -e "| ${BENCHMARK}\t| ${MODE}\t| ${APP_METHOD_COUNT}\t| ${BASE_RECALL}\t| ${RECALL}\t| ${APP_REACHABLE_DELTA}\t| ${ANALYSIS_TIME_DELTA}\t| ${FACTS_TIME_DELTA}\t| ${ADDED_ENTRY_POINTS}\t|"
}

function setIDs() {
    local BENCHMARK="$1"
    ID_BASE="native-test-${BENCHMARK}-base"
    ID_SCANNER="native-test-${BENCHMARK}-scanner"
    ID_SCANNER_LOCAL_OBJ="${ID_SCANNER}-loc-obj"
    ID_SCANNER_LOCAL_RAD="${ID_SCANNER}-loc-rad"
    ID_SCANNER_SMART="${ID_SCANNER}-smart"
    ID_SCANNER_OFFSETS1="${ID_SCANNER}-dist-${STRING_DISTANCE1}"
    ID_SCANNER_OFFSETS2="${ID_SCANNER}-dist-${STRING_DISTANCE2}"
    ID_HEAPDL="native-test-${BENCHMARK}-heapdl"
}

function runDoop() {
    local INPUT="$1"
    local BENCHMARK="$2"
    local PLATFORM="$3"
    local HPROF="$4"
    # echo HPROF="${HPROF}"
    # if [ ! -f ${HPROF} ]; then
    #     echo "Error, file does not exist: ${HPROF}"
    #     return
    # fi
    setIDs "${BENCHMARK}"
    pushd ${DOOP_HOME} &> /dev/null
    date
    BASE_OPTS="--platform ${PLATFORM} --timeout ${TIMEOUT}" #  --no-standard-exports"
    # 1. Base analysis.
    ./doop -i ${INPUT} -a ${ANALYSIS} --id ${ID_BASE} ${BASE_OPTS} |& tee ${CURRENT_DIR}/${ID_BASE}.log
    if [ "${HPROF}" != "" ]; then
        # 2. HeapDL analysis, for comparison.
        ./doop -i ${INPUT} -a ${ANALYSIS} --id ${ID_HEAPDL} ${BASE_OPTS} --heapdl-file ${HPROF} |& tee ${CURRENT_DIR}/${ID_HEAPDL}.log
    fi
    # 3. Native scanner, default mode.
    # ./doop -i ${INPUT} -a ${ANALYSIS} --id ${ID_SCANNER} ${BASE_OPTS} --scan-native-code |& tee ${CURRENT_DIR}/${ID_SCANNER}.log
    # 4. Native scanner, use only localized strings (binutils/Radare2 modes).
    # ./doop -i ${INPUT} -a ${ANALYSIS} --id ${ID_SCANNER_LOCAL_OBJ} ${BASE_OPTS} --scan-native-code --only-precise-native-strings |& tee ${CURRENT_DIR}/${ID_SCANNER_LOCAL_OBJ}.log
    ./doop -i ${INPUT} -a ${ANALYSIS} --id ${ID_SCANNER_LOCAL_RAD} ${BASE_OPTS} --scan-native-code --only-precise-native-strings --use-radare |& tee ${CURRENT_DIR}/${ID_SCANNER_LOCAL_RAD}.log
    # 5. Native scanner, "smart native targets" mode.
    # ./doop -i ${INPUT} -a ${ANALYSIS} --id ${ID_SCANNER_SMART} ${BASE_OPTS} --scan-native-code --smart-native-targets |& tee ${CURRENT_DIR}/${ID_SCANNER_SMART}.log
    # 6. Native scanner, "use string locality" mode.
    # ./doop -i ${INPUT} -a ${ANALYSIS} --id ${ID_SCANNER_OFFSETS1} ${BASE_OPTS} --scan-native-code --use-string-locality --native-strings-distance ${STRING_DISTANCE1} |& tee ${CURRENT_DIR}/${ID_SCANNER_OFFSETS1}.log
    # if [ "${BENCHMARK}" == "chrome" ]; then
    #     ./doop -i ${INPUT} -a ${ANALYSIS} --id ${ID_SCANNER_OFFSETS2} ${BASE_OPTS} --scan-native-code --use-string-locality --native-strings-distance ${STRING_DISTANCE2} |& tee ${CURRENT_DIR}/${ID_SCANNER_OFFSETS2}.log
    # fi
    popd &> /dev/null
}

function printLine() {
    for i in $(seq 1 $1); do
        echo -n '-'
    done
    echo
}

function printStatsTable() {
    let COL1_END="20"
    let COL2_END="${COL1_END} + 15"
    let COL3_END="${COL2_END} + 10"
    let COL4_END="${COL3_END} + 17"
    let COL5_END="${COL4_END} + 22"
    let COL6_END="${COL5_END} + 30"
    let COL7_END="${COL6_END} + 25"
    let COL8_END="${COL7_END} + 25"
    let COL9_END="${COL8_END} + 10"
    local LAST_COL=${COL9_END}
    tabs ${COL1_END},${COL2_END},${COL3_END},${COL4_END},${COL5_END},${COL6_END},${COL7_END},${COL8_END},${COL9_END}
    printLine ${LAST_COL}
    echo -e "| Benchmark\t| Mode\t| App    \t| Base  \t| Recall\t| +App-reachable    \t| +Analysis time    \t| +Factgen time\t| +entry\t|"
    echo -e "|          \t|     \t| methods\t| recall\t|       \t|  (incr. over base)\t|  (incr. over base)\t|              \t|  points\t|"
    printLine ${LAST_COL}
    # for BENCHMARK in androidterm chrome instagram 009-native
    for BENCHMARK in "chrome" "instagram" "009-native" "aspectj-1.6.9" "log4j-1.2.16" "lucene-4.3.0" "tomcat-7.0.2" "vlc"
    do
        setIDs "${BENCHMARK}"
        MODES=( "" "-loc-obj" "-loc-rad" "-smart" "-dist-${STRING_DISTANCE1}" )
        # if [ "${BENCHMARK}" == "chrome" ]; then
        #     MODES=( "" "-loc-obj" "-loc-rad" "-smart" "-dist-${STRING_DISTANCE1}" "-dist-${STRING_DISTANCE2}" )
        #     # MODES=( "" "-loc-rad" "-dist-${STRING_DISTANCE1}" "-dist-${STRING_DISTANCE2}" )
        # else
        #     MODES=( "" "-loc-obj" "-loc-rad" "-smart" "-dist-${STRING_DISTANCE1}" )
        #     # MODES=( "" "-loc-rad" "-dist-${STRING_DISTANCE1}" )
        # fi
        for MODE in "${MODES[@]}"
        do
            local ID_STATIC="${ID_SCANNER}${MODE}"
            printStatsRow "${BENCHMARK}" "${ID_BASE}" "${ID_STATIC}" "${ID_HEAPDL}" "${MODE}"
        done
        printLine ${LAST_COL}
    done
}

function analyzeAspectJ() {
    local BASE_DIR="${XCORPUS_DIR}/data/qualitas_corpus_20130901/aspectj-1.6.9"
    APP_INPUTS="${XCORPUS_EXT_DIR}/repackaged/aspectj-1.6.9 ${XCORPUS_EXT_DIR}/native/aspectj/x86_64-1.0.100-v20070510.jar"
    analyzeXCorpusBenchmark "aspectj-1.6.9"
}

function analyzeLucene() {
    local BASE_DIR="${XCORPUS_DIR}/data/qualitas_corpus_20130901/lucene-4.3.0"
    APP_INPUTS="${XCORPUS_EXT_DIR}/repackaged/lucene-4.3.0 ${XCORPUS_EXT_DIR}/native/lucene/lucene-misc-4.3.0/org/apache/lucene/store/libNativePosixUtil.so.jar"
    analyzeXCorpusBenchmark "lucene-4.3.0"
}

function analyzeLog4J() {
    local BASE_DIR="${XCORPUS_DIR}/data/qualitas_corpus_20130901/log4j-1.2.16"
    APP_INPUTS="${BASE_DIR}/project/bin.zip ${BASE_DIR}/project/builtin-tests.zip ${BASE_DIR}/.xcorpus/evosuite-tests.zip"
    analyzeXCorpusBenchmark "log4j-1.2.16"
}

function analyzeTomcat() {
    local BASE_DIR="${XCORPUS_DIR}/data/qualitas_corpus_20130901/tomcat-7.0.2"
    APP_INPUTS="${XCORPUS_EXT_DIR}/repackaged/tomcat-7.0.2 ${BASE_DIR}/project/builtin-tests.zip ${XCORPUS_EXT_DIR}/native/tomcat/win64/tcnative.dll_win64_x64.jar ${XCORPUS_EXT_DIR}/native/tomcat/mandriva/libtcnative-1.so.jar"
    analyzeXCorpusBenchmark "tomcat-7.0.2"
}

function analyzeXCorpusBenchmark() {
    local BENCHMARK="$1"
    local BASE_OPTS="-i ${APP_INPUTS} -a ${ANALYSIS} --discover-main-methods --discover-tests --timeout ${TIMEOUT} --main DUMMY"

    if [ "${XCORPUS_DIR}" == "" ]; then
        echo "ERROR: cannot analyze benchmark '${BENCHMARK}', please set environment variable XCORPUS_DIR to point to the XCorpus directory."
        exit
    elif [ "${XCORPUS_EXT_DIR}" == "" ]; then
        echo "ERROR: cannot analyze benchmark '${BENCHMARK}', please set environment variable XCORPUS_EXT_DIR to point to the XCorpus extension directory."
        exit
    else
        echo "Original XCorpus directory: ${XCORPUS_DIR}"
        echo "XCorpus native extension directory: ${XCORPUS_EXT_DIR}"
    fi

    setIDs "${BENCHMARK}"
    pushd ${DOOP_HOME} &> /dev/null
    date
    set -x
    # 1. Base analysis.
    ./doop ${BASE_OPTS} --id ${ID_BASE} |& tee ${CURRENT_DIR}/${ID_BASE}.log
    # 2. Native scanner, default mode.
    # ./doop ${BASE_OPTS} --id ${ID_SCANNER} --scan-native-code |& tee ${CURRENT_DIR}/${ID_SCANNER}.log
    # 3. Native scanner, use only localized strings (binutils/Radare2 modes).
    # ./doop ${BASE_OPTS} --id ${ID_SCANNER_LOCAL_OBJ} --scan-native-code --only-precise-native-strings |& tee ${CURRENT_DIR}/${ID_SCANNER_LOCAL_OBJ}.log
    ./doop ${BASE_OPTS} --id ${ID_SCANNER_LOCAL_RAD} --scan-native-code --only-precise-native-strings --use-radare |& tee ${CURRENT_DIR}/${ID_SCANNER_LOCAL_RAD}.log
    set +x
    popd &> /dev/null
}

trap "exit" INT

if [ "${RUN_ANALYSIS}" == "1" ]; then
    # Generate java.hprof with "make capture_hprof".
    runDoop ${SERVER_ANALYSIS_TESTS}/009-native/build/libs/009-native.jar 009-native java_8 ${SERVER_ANALYSIS_TESTS}/009-native/java.hprof

    # Chrome.
    runDoop ${DOOP_BENCHMARKS}/android-benchmarks/com.android.chrome_57.0.2987.132-298713212_minAPI24_x86_nodpi_apkmirror.com.apk chrome ${ANDROID_PLATFORM} ${DOOP_BENCHMARKS}/android-benchmarks/com.android.chrome.hprof.gz

    # Instagram.
    runDoop ${DOOP_BENCHMARKS}/android-benchmarks/com.instagram.android_10.5.1-48243323_minAPI16_x86_nodpi_apkmirror.com.apk instagram ${ANDROID_PLATFORM} ${DOOP_BENCHMARKS}/android-benchmarks/com.instagram.android.hprof.gz

    # Androidterm.
    # runDoop ${DOOP_BENCHMARKS}/android-benchmarks/jackpal.androidterm-1.0.70-71-minAPI4.apk androidterm ${ANDROID_PLATFORM} ${DOOP_BENCHMARKS}/android-benchmarks/jackpal.androidterm.hprof.gz

    # VLC.
    runDoop ${DOOP_BENCHMARKS}/android-benchmarks/org.videolan.vlc_13010707.apk vlc ${ANDROID_PLATFORM} ""

    # AspectJ.
    analyzeAspectJ

    # Lucene.
    analyzeLucene

    # Log4j.
    analyzeLog4J

    # Tomcat.
    analyzeTomcat
fi

printStatsTable ${BENCHMARK}