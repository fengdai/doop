package org.clyze.doop.core

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.clyze.analysis.AnalysisFactory
import org.clyze.analysis.AnalysisFamily
import org.clyze.analysis.AnalysisOption
import org.clyze.analysis.BooleanAnalysisOption
import org.clyze.doop.input.DefaultInputResolutionContext
import org.clyze.doop.input.InputResolutionContext
import org.clyze.utils.CheckSum
import org.clyze.utils.FileOps
import org.clyze.utils.Helper

import java.util.jar.Attributes
import java.util.jar.JarFile

/**
 * A Factory for creating Analysis objects.
 *
 * All the methods invoked by newAnalysis (either directly or indirectly) could
 * have been static helpers (e.g. entailed in the Helper class) but they are
 * protected instance methods to allow descendants to customize all possible
 * aspects of Analysis creation.
 */
class DoopAnalysisFactory implements AnalysisFactory<DoopAnalysis> {

    Log logger = LogFactory.getLog(getClass())
    static final char[] EXTRA_ID_CHARACTERS = '_-+.'.toCharArray()
    static final String HASH_ALGO = "SHA-256"
    static final Set<String> availablePlatforms =
        [ // JDKs
          "java_3", "java_4", "java_5", "java_6", "java_7", "java_8",
          // Android compiled from sources
          "android_22_fulljars", "android_25_fulljars",
          // Android API stubs (from the SDK)
          "android_7_stubs" , "android_15_stubs", "android_16_stubs", "android_17_stubs",
          "android_18_stubs", "android_19_stubs", "android_20_stubs", "android_21_stubs",
          "android_22_stubs", "android_23_stubs", "android_24_stubs", "android_25_stubs",
          "android_26_stubs",
          // Android-Robolectric
          "android_26_robolectric"
        ]
    static String platformsLib

    static final Map<String, Set<String>> artifactsForPlatform =
            ["java_3" : ["rt.jar"],
             "java_4" : ["rt.jar", "jce.jar", "jsse.jar"],
             "java_5" : ["rt.jar", "jce.jar", "jsse.jar"],
             "java_6" : ["rt.jar", "jce.jar", "jsse.jar"],
             "java_7" : ["rt.jar", "jce.jar", "jsse.jar", "tools.jar"],
             "java_8" : ["rt.jar", "jce.jar", "jsse.jar"],
             "android_17" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar"],
             "android_18" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar"],
             "android_19" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar"],
             "android_20" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar"],
             "android_21" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar"],
             "android_22" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar",
                             "optional/org.apache.http.legacy.jar"],
             "android_23" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar",
                             "optional/org.apache.http.legacy.jar", "android-stubs-src.jar"],
             "android_24" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar",
                             "optional/org.apache.http.legacy.jar", "android-stubs-src.jar"],
             "android_25" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar",
                             "optional/org.apache.http.legacy.jar", "android-stubs-src.jar"],
             "android_26" : ["android.jar", "data/icu4j.jar", "data/layoutlib.jar", "uiautomator.jar",
                             "optional/org.apache.http.legacy.jar", "android-stubs-src.jar"]
            ]
    static final availableConfigurations = [
            "introspective" : "IntrospectiveConfiguration",
            "context-insensitive" : "ContextInsensitiveConfiguration",
            "context-insensitive-plus" : "ContextInsensitivePlusConfiguration",
            "context-insensitive-plusplus" : "ContextInsensitivePlusPlusConfiguration",
            "1-call-site-sensitive" : "OneCallSiteSensitiveConfiguration",
            "1-call-site-sensitive+heap" : "OneCallSiteSensitivePlusHeapConfiguration",
            "1-type-sensitive" : "OneTypeSensitiveConfiguration",
            "1-type-sensitive+heap" : "OneTypeSensitivePlusHeapConfiguration",
            "1-object-sensitive" : "OneObjectSensitiveConfiguration",
            "1-object-sensitive+heap" : "OneObjectSensitivePlusHeapConfiguration",
            "2-call-site-sensitive" : "TwoCallSiteSensitiveConfiguration",
            "2-call-site-sensitive+heap" : "TwoCallSiteSensitivePlusHeapConfiguration",
            "2-call-site-sensitive+2-heap" : "TwoCallSiteSensitivePlusTwoHeapConfiguration",
            "2-type-sensitive" : "TwoTypeSensitiveConfiguration",
            "2-type-sensitive+heap" : "TwoTypeSensitivePlusHeapConfiguration",
            "2-object-sensitive" : "TwoObjectSensitiveConfiguration",
            "2-object-sensitive+heap" : "TwoObjectSensitivePlusHeapConfiguration",
            "2-object-sensitive+2-heap" : "TwoObjectSensitivePlusTwoHeapConfiguration",
            "3-object-sensitive+3-heap" : "ThreeObjectSensitivePlusThreeHeapConfiguration",
            "2-type-object-sensitive+heap" : "TwoObjectSensitivePlusHeapConfiguration",
            "2-type-object-sensitive+2-heap" : "TwoObjectSensitivePlusTwoHeapConfiguration",
            "3-type-sensitive+2-heap" : "ThreeTypeSensitivePlusTwoHeapConfiguration",
            "3-type-sensitive+3-heap" : "ThreeTypeSensitivePlusThreeHeapConfiguration",
            "selective-2-object-sensitive+heap" : "SelectiveTwoObjectSensitivePlusHeapConfiguration",
            "partitioned-2-object-sensitive_heap" : "PartitionedTwoObjectSensitivePlusHeapConfiguration",
    ]

    /**
     * A helper class that acts as an intermediate holder of the analysis variables.
     */
    protected static class AnalysisVars {
        String name
        Map<String, AnalysisOption> options
        List<String> inputFilePaths
        List<String> libraryFilePaths
        List<String> platformFilePaths
        List<File>   inputFiles
        List<File>   libraryFiles
        List<File>   platformFiles

        @Override
        String toString() {
            return [
                    name:              name,
                    options:           options.values().toString(),
                    inputFilePaths:    inputFilePaths.toString(),
                    libraryFilePaths:  libraryFilePaths.toString(),
                    platformFilePaths: platformFilePaths.toString(),
                    inputFiles:        inputFiles.toString(),
                    libraryFiles:      libraryFiles.toString(),
                    platformFiles:     platformFiles.toString()
            ].toString()
        }
    }

    private static getConfiguration(String analysisName) {
        return availableConfigurations.get(analysisName)
    }

    /**
     * Creates a new analysis, verifying the correctness of its id, name, options and inputFiles using
     * the supplied input resolution mechanism.
     * If the supplied id is empty or null, an id will be generated automatically.
     * Otherwise the id will be validated:
     * - if it is valid, it will be used to identify the analysis,
     * - if it is invalid, an exception will be thrown.
     */
    DoopAnalysis newAnalysis(String id, String name, Map<String, AnalysisOption> options, InputResolutionContext context) {
        options.CONFIGURATION.value = getConfiguration(name)

        def vars = processOptions(name, options, context)

        checkAnalysis(name, options)
        if (options.LB3.value)
            checkLogicBlox(vars)

        //init the environment used for executing commands
        Map<String, String> commandsEnv = initExternalCommandsEnvironment(vars)

        // if not empty or null
        def analysisId = id ? validateUserSuppliedId(id) : generateId(vars)

        def cacheId = generateCacheID(vars)

        def outDir = createOutputDirectory(vars, analysisId)

        def cacheDir

        if (options.X_START_AFTER_FACTS.value) {
            cacheDir = new File(options.X_START_AFTER_FACTS.value)
            FileOps.findDirOrThrow(cacheDir, "Invalid user-provided facts directory: $cacheDir")
        }
        else {
            cacheDir = new File("${Doop.doopCache}/$cacheId")
            checkAppGlob(vars)
        }

        DoopAnalysis analysis
        if (options.LB3.value) {
            if (name != "sound-may-point-to") {
                options.CFG_ANALYSIS.value = false
                analysis = new ClassicAnalysis(
                        analysisId,
                        name.replace(File.separator, "-"),
                        options,
                        context,
                        outDir,
                        cacheDir,
                        vars.inputFiles,
                        vars.libraryFiles,
                        vars.platformFiles,
                        commandsEnv)
            } else {
                analysis = new SoundMayAnalysis(
                        analysisId,
                        name.replace(File.separator, "-"),
                        options,
                        context,
                        outDir,
                        cacheDir,
                        vars.inputFiles,
                        vars.libraryFiles,
                        vars.platformFiles,
                        commandsEnv)
            }
        } else {
            options.CFG_ANALYSIS.value = false
            analysis = new SouffleAnalysis(
                    analysisId,
                    name.replace(File.separator, "-"),
                    options,
                    context,
                    outDir,
                    cacheDir,
                    vars.inputFiles,
                    vars.libraryFiles,
                    vars.platformFiles,
                    commandsEnv)
        }
        logger.debug "Created new analysis"
        return analysis
    }

    /**
     * Creates a new analysis, verifying the correctness of its name, options and inputFiles using
     * the default input resolution mechanism.
     */
    @Override
    DoopAnalysis newAnalysis(AnalysisFamily family, String id, String name, Map<String, AnalysisOption> options, List<String> inputFilePaths, List<String> libraryFilePaths) {
        DefaultInputResolutionContext context = new DefaultInputResolutionContext()
        context.add(inputFilePaths, false)
        context.add(libraryFilePaths, true)
        return newAnalysis(id, name, options, context)
    }

    protected void checkAnalysis(String name, Map<String, AnalysisOption> options) {
        logger.debug "Verifying analysis name: $name"
        def analysisPath
        if (options.LB3.value) 
          analysisPath = "${Doop.analysesPath}/${name}/analysis.logic"
        else
          analysisPath = "${Doop.souffleAnalysesPath}/${name}/analysis.dl"
        FileOps.findFileOrThrow(analysisPath, "Unsupported analysis: $name")
    }

    protected static String validateUserSuppliedId(String id) {
        def trimmed = id.trim()
        def isValid = trimmed.toCharArray().every {
            c -> Character.isLetter(c) || Character.isDigit(c) || c in EXTRA_ID_CHARACTERS
        }

        if (!isValid) {
            throw new RuntimeException("Invalid analysis id: $id. The id should contain only letters, digits, " +
                    "${EXTRA_ID_CHARACTERS.collect{"'$it'"}.join(', ')}.")
        }
        return trimmed
    }

    protected static File createOutputDirectory(AnalysisVars vars, String id) {
        def outDir = new File("${Doop.doopOut}/${vars.name}/${id}")
        FileUtils.deleteQuietly(outDir)
        outDir.mkdirs()
        FileOps.findDirOrThrow(outDir, "Could not create analysis directory: ${outDir}")
        return outDir
    }

    protected String generateId(AnalysisVars vars) {
        Collection<String> idComponents = vars.options.keySet().findAll {
            !Doop.OPTIONS_EXCLUDED_FROM_ID_GENERATION.contains(it)
        }.collect {
            String option -> return vars.options.get(option).toString()
        }
        idComponents = [vars.name] + vars.inputFilePaths + vars.libraryFilePaths + idComponents
        logger.debug("ID components: $idComponents")
        def id = idComponents.join('-')

        return CheckSum.checksum(id, HASH_ALGO)
    }

    protected String generateCacheID(AnalysisVars vars) {
        Collection<String> idComponents = vars.options.values()
                .findAll { it.forCacheID }
                .collect { option -> option.toString() }

        Collection<String> checksums = []
        checksums += vars.inputFiles.collect { file -> CheckSum.checksum(file, HASH_ALGO) }
        checksums += vars.libraryFiles.collect { file -> CheckSum.checksum(file, HASH_ALGO) }
        checksums += vars.platformFiles.collect { file -> CheckSum.checksum(file, HASH_ALGO) }

        if (vars.options.TAMIFLEX.value && vars.options.TAMIFLEX.value != "dummy")
            checksums += [CheckSum.checksum(new File(vars.options.TAMIFLEX.value.toString()), HASH_ALGO)]

        idComponents = checksums + idComponents

        logger.debug("Cache ID components: $idComponents")
        def id = idComponents.join('-')

        return CheckSum.checksum(id, HASH_ALGO)
    }

    /**
     * Generates a list of the platform library arguments for soot
     */
    protected static List<String> platform(Map<String, AnalysisOption> options) {
        def platformInfo = options.PLATFORM.value.toString().tokenize("_")
        if (platformInfo.size() < 2) {
            throw new RuntimeException("Invalid platform ${platformInfo}")
        }
        def (platform, version) = [platformInfo[0], platformInfo[1].toInteger()]

        def platformArtifactPaths = []
        switch(platform) {
            case "java":
                if (platformInfo.size == 2) {
                    def files = getArtifactsForPlatform(options.PLATFORM.value.toString(), options.PLATFORMS_LIB.value.toString())
                    files.each({File file ->
                        platformArtifactPaths.add(file.canonicalPath)
                    })
                }
                else if (platformInfo.size == 3) {
                    String minorVersion = platformInfo[2]

                    switch (version) {
                        case 7:
                        case 8:
                            String platformPath = "${options.PLATFORMS_LIB.value}/JREs/jre1.${version}.0_${minorVersion}/lib"
                            platformArtifactPaths = ["${platformPath}/rt.jar",
                                     "${platformPath}/jce.jar",
                                     "${platformPath}/jsse.jar"]
                            break
                        default:
                            throw new RuntimeException("Invalid JRE version: $version")
                    }
                }
                else {
                    throw new RuntimeException("Invalid JRE version: $version")
                }
                // generate the JRE constant for the preprocessor
                def jreOption = new BooleanAnalysisOption(
                        id:"JRE1"+version,
                        value:true,
                        forPreprocessor: true
                )
                options[(jreOption.id)] = jreOption
                break
            case "android":
                if (platformInfo.size < 3) {
                    throw new RuntimeException("Invalid Android platform: $platformInfo")
                }
                // If the user has given a platform ending in
                // "_fulljars", then use the "fulljars" subdirectory of
                // the platforms library, otherwise use the "stubs"
                // one. This permits use of two Android system JARs
                // side-by-side: either the stubs provided by the
                // official Android SDK or a custom Android build.
                String libFlavor = platformInfo[2]
                if (![ "stubs", "fulljars", "robolectric" ].contains(libFlavor)) {
                    throw new RuntimeException("Invalid Android platform: $platformInfo")
                }
                String path = "${options.PLATFORMS_LIB.value}/Android/${libFlavor}/Android/Sdk/platforms/android-${version}"
                options.ANDROID.value = true

                switch(version) {
                    case 7:
                    case 15:
                        platformArtifactPaths = ["${path}/android.jar",
                                 "${path}/data/layoutlib.jar"]
                        break
                    case 16:
                        platformArtifactPaths = ["${path}/android.jar",
                                 "${path}/data/layoutlib.jar",
                                 "${path}/uiautomator.jar"]
                        break
                    case 17:
                    case 18:
                    case 19:
                    case 20:
                    case 21:
                    case 22:
                        platformArtifactPaths = ["${path}/android.jar",
                                 "${path}/data/icu4j.jar",
                                 "${path}/data/layoutlib.jar",
                                 "${path}/uiautomator.jar"]
                        break
                    case 23:
                        platformArtifactPaths = ["${path}/android.jar",
                                 "${path}/optional/org.apache.http.legacy.jar",
                                 "${path}/data/layoutlib.jar",
                                 "${path}/uiautomator.jar"]
                        break
                    case 24:
                    case 25:
                    case 26:
                        platformArtifactPaths = ["${path}/android.jar",
                                 "${path}/android-stubs-src.jar",
                                 "${path}/optional/org.apache.http.legacy.jar",
                                 "${path}/data/layoutlib.jar",
                                 "${path}/uiautomator.jar"]
                        break
                    default:
                        throw new RuntimeException("Invalid android version: $version")
                }
                if (libFlavor == "robolectric") {
                    String roboJRE = "java_8"
                    println "Using ${roboJRE} with Robolectric"
                    def files = getArtifactsForPlatform(roboJRE, options.PLATFORMS_LIB.value.toString())
                    files.each { platformArtifactPaths.add(it.canonicalPath) }
                }
                break
            default:
                throw new RuntimeException("Invalid platform: $platform")
        }
        return platformArtifactPaths
    }

    /**
     * Processes the options of the analysis.
     */
    protected AnalysisVars processOptions(String name, Map<String, AnalysisOption> options, InputResolutionContext context) {

        logger.debug "Processing analysis options"


        def inputFilePaths
        def libraryFilePaths
        def platformFilePaths
        def inputFiles
        def libraryFiles
        def platformFiles

        if (!options.X_START_AFTER_FACTS.value) {
            inputFilePaths = context.inputs()            
            libraryFilePaths = context.libraries()            
            platformFilePaths = platform(options)            

            logger.debug "Resolving inputs and libraries"
            context.resolve()
            
            inputFiles = context.getAllInputs()
            logger.debug "Input file paths: $inputFilePaths -> $inputFiles"
            libraryFiles = context.getAllLibraries()
            logger.debug "Library file paths: $libraryFilePaths -> $libraryFiles"
            platformFiles = resolve(platformFilePaths, true)
            logger.debug "Platform file paths: $platformFilePaths -> $platformFiles"
        }

        if (options.DACAPO.value || options.DACAPO_BACH.value) {
            if (!options.X_START_AFTER_FACTS.value) {
                def inputJarName = inputFilePaths[0]
                def deps = inputJarName.replace(".jar", "-deps.jar")
                if (!inputFilePaths.contains(deps) && !libraryFilePaths.contains(deps)) {
                    libraryFilePaths.add(deps)
                    context.resolve()
                    libraryFiles = context.getAllLibraries()
                }

                if (!options.REFLECTION.value && !options.TAMIFLEX.value)
                    options.TAMIFLEX.value = resolve([inputJarName.replace(".jar", "-tamiflex.log")], false)[0]

                def benchmark = FilenameUtils.getBaseName(inputJarName)
                logger.info "Running " + (options.DACAPO.value ? "dacapo" : "dacapo-bach") + " benchmark: $benchmark"
            }
            else {
                options.TAMIFLEX.value = "dummy"
            }
        }

        if (options.MAIN_CLASS.value) {
            logger.debug "The main class is set to ${options.MAIN_CLASS.value}"
        } else {
            if (!options.X_START_AFTER_FACTS.value && !options.IGNORE_MAIN_METHOD.value) {
                if (inputFiles[0] == null) {
                    throw new RuntimeException("Error: no input files")
                }
                JarFile jarFile = new JarFile(inputFiles[0])
                //Try to read the main class from the manifest contained in the jar
                def main = jarFile.getManifest()?.getMainAttributes()?.getValue(Attributes.Name.MAIN_CLASS)
                if (main) {
                    logger.debug "The main class is automatically set to ${main}"
                    options.MAIN_CLASS.value = main
                } else {
                    //Check whether the jar contains a class with the same name
                    def jarName = FilenameUtils.getBaseName(jarFile.getName())
                    if (jarFile.getJarEntry("${jarName}.class")) {
                        logger.debug "The main class is automatically set to ${jarName}"
                        options.MAIN_CLASS.value = jarName
                    }
                }
            }
        }

        if (options.DYNAMIC.value) {
            List<String> dynFiles = options.DYNAMIC.value as List<String>
            dynFiles.each { String dynFile ->
                FileOps.findFileOrThrow(dynFile, "The DYNAMIC option is invalid: ${dynFile}")
                logger.debug "The DYNAMIC option has been set to ${dynFile}"
            }
        }

        if (options.TAMIFLEX.value && options.TAMIFLEX.value != "dummy") {
            def tamFile = options.TAMIFLEX.value.toString()
            FileOps.findFileOrThrow(tamFile, "The TAMIFLEX option is invalid: ${tamFile}")
        }


        if (options.DISTINGUISH_ALL_STRING_BUFFERS.value &&
                options.DISTINGUISH_STRING_BUFFERS_PER_PACKAGE.value) {
            logger.warn "\nWARNING: multiple distinguish-string-buffer flags. 'All' overrides.\n"
        }

        if (options.NO_MERGE_LIBRARY_OBJECTS.value) {
            options.MERGE_LIBRARY_OBJECTS_PER_METHOD.value = false
        }

        if (options.MERGE_LIBRARY_OBJECTS_PER_METHOD.value && options.CONTEXT_SENSITIVE_LIBRARY_ANALYSIS.value) {
            logger.warn "\nWARNING, possible inconsistency: context-sensitive library analysis with merged objects.\n"
        }

        if (options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value &&
            options.DISTINGUISH_ALL_STRING_CONSTANTS.value) {
            throw new RuntimeException("Error: options " + options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.name + " and " + options.DISTINGUISH_ALL_STRING_CONSTANTS.name + " are mutually exclusive.\n")
        }

        if (options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value) {
            options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value = true
            options.DISTINGUISH_ALL_STRING_CONSTANTS.value = false
        }

        if (options.DISTINGUISH_ALL_STRING_CONSTANTS.value) {
            options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value = false
            options.DISTINGUISH_ALL_STRING_CONSTANTS.value = true
        }

        if (options.REFLECTION_CLASSIC.value) {
            if (options.DISTINGUISH_ALL_STRING_CONSTANTS.value) {
                throw new RuntimeException("Error: options " + options.REFLECTION_CLASSIC.name + " and " + options.DISTINGUISH_ALL_STRING_CONSTANTS.name + " are mutually exclusive.\n")
            }
            options.DISTINGUISH_ALL_STRING_CONSTANTS.value = false
            options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value = true
            options.REFLECTION.value = true
            options.REFLECTION_SUBSTRING_ANALYSIS.value = true
            options.DISTINGUISH_STRING_BUFFERS_PER_PACKAGE.value = true
            options.TAMIFLEX.value = null
        }

        if (options.TAMIFLEX.value) {
            options.REFLECTION.value = false
        }

        if (options.NO_SSA.value) {
            options.SSA.value = false
        }

        if (options.MUST.value) {
            options.MUST_AFTER_MAY.value = true
        }

        if (options.X_DRY_RUN.value) {
            options.X_STATS_NONE.value = true
            options.X_SERVER_LOGIC.value = true
            if (options.CACHE.value) {
                logger.warn "\nWARNING: Doing a dry run of the analysis while using cached facts might be problematic!\n"
            }
        }

        // If server mode is enabled, don't produce statistics.
        if (options.X_SERVER_LOGIC.value) {
            options.X_STATS_FULL.value = false
            options.X_STATS_DEFAULT.value = false
            options.X_STATS_NONE.value = true
        }

        // If no stats option is given, select default stats.
        if (!options.X_STATS_FULL.value && !options.X_STATS_DEFAULT.value &&
            !options.X_STATS_NONE.value && !options.X_STATS_AROUND.value) {
            options.X_STATS_DEFAULT.value = true
        }

        if (options.REFLECTION_DYNAMIC_PROXIES.value) {
            if (!options.REFLECTION.value) {
                String message = "\nWARNING: Dynamic proxy support without standard reflection support, using custom 'opt-reflective' reflection rules."
                if (!options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value &&
                    !options.DISTINGUISH_ALL_STRING_CONSTANTS.value) {
                    message += "\nWARNING: 'opt-reflective' may not work optimally, one of these flags is suggested: --" + options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.name + ", --" + options.DISTINGUISH_ALL_STRING_CONSTANTS.name
                }
                logger.warn message
            }
        }

        if (!options.REFLECTION.value) {
            if (options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value ||
                    options.REFLECTION_SUBSTRING_ANALYSIS.value ||
                    options.REFLECTION_CONTEXT_SENSITIVITY.value ||
                    options.REFLECTION_HIGH_SOUNDNESS_MODE.value ||
                    options.REFLECTION_SPECULATIVE_USE_BASED_ANALYSIS.value ||
                    options.REFLECTION_INVENT_UNKNOWN_OBJECTS.value ||
                    options.REFLECTION_REFINED_OBJECTS.value) {
                logger.warn "\nWARNING: Probable inconsistent set of Java reflection flags!\n"
            } else if (options.TAMIFLEX.value) {
                logger.warn "\nWARNING: Handling of Java reflection via Tamiflex logic!\n"
            } else {
                logger.warn "\nWARNING: Handling of Java reflection is disabled!\n"
            }
        }

        options.values().each {
            if (it.argName && it.value && it.validValues && !(it.value in it.validValues))
                throw new RuntimeException("Invalid value `$it.value` for option: $it.name")
        }

        options.values().findAll { it.isMandatory }.each {
            if (!it.value) throw new RuntimeException("Missing mandatory argument: $it.name")
        }

        logger.debug "---------------"
        AnalysisVars vars = new AnalysisVars(
                name:              name,
                options:           options,
                inputFilePaths:    inputFilePaths,
                libraryFilePaths:  libraryFilePaths,
                platformFilePaths: platformFilePaths,
                inputFiles:        inputFiles,
                libraryFiles:      libraryFiles,
                platformFiles:     platformFiles
        )
        logger.debug vars
        logger.debug "---------------"

        return vars
    }

    static List<File> resolve(List<String> filePaths, boolean isLib) {
        def context = new DefaultInputResolutionContext()
        filePaths.each { f -> context.add(f, isLib) }
        context.resolve()
        return isLib? context.getAllLibraries() : context.getAllInputs()
    }

    /**
     * Determines application classes.
     *
     * If an app regex is not present, it generates one.
     */
    protected void checkAppGlob(AnalysisVars vars) {
        if (!vars.options.APP_REGEX.value) {
            logger.debug "Generating app regex"

            //We process only the first jar for determining the application classes
            /*
            Set excluded = ["*", "**"] as Set
            analysis.jars.drop(1).each { Dependency jar ->
                excluded += Helper.getPackages(jar.input())
            }

            Set<String> packages = Helper.getPackages(analysis.jars[0].input()) - excluded
            */
            Set<String> packages = Helper.getPackages(vars.inputFiles[0])
            vars.options.APP_REGEX.value = packages.sort().join(':')
        }
    }

    /**
     * Verifies the correctness of the LogicBlox related options
     */
    protected void checkLogicBlox(AnalysisVars vars) {

        //BLOX_OPTS is set by the main method

        AnalysisOption lbhome = vars.options.LOGICBLOX_HOME

        logger.debug "Verifying LogicBlox home: ${lbhome.value}"

        def lbHomeDir = FileOps.findDirOrThrow(lbhome.value as String, "The ${lbhome.id} value is invalid: ${lbhome.value}")

        def oldldpath = System.getenv("LD_LIBRARY_PATH")
        vars.options.LD_LIBRARY_PATH.value = lbHomeDir.getAbsolutePath() + "/bin" + ":" + oldldpath
        def bloxbatch = lbHomeDir.getAbsolutePath() + "/bin/bloxbatch"
        FileOps.findFileOrThrow(bloxbatch, "The bloxbatch file is invalid: $bloxbatch")
        vars.options.BLOXBATCH.value = bloxbatch
    }

    /**
     * Initializes the external commands environment of the given analysis, by:
     * <ul>
     *     <li>adding the LD_LIBRARY_PATH option to the current environment
     *     <li>modifying PATH to also include the LD_LIBRARY_PATH option
     *     <li>adding the LOGICBLOX_HOME option to the current environment
     *     <li>adding the DOOP_HOME to the current environment
     *     <li>adding the LB_PAGER_FORCE_START and the LB_MEM_NOWARN to the current environment
     *     <li>adding the variables/paths/tweaks to meet the lb-env-bin.sh requirements of the pa-datalog distro
     * </ul>
     */
    protected Map<String, String> initExternalCommandsEnvironment(AnalysisVars vars) {

        logger.debug "Initializing the environment of the external commands"

        Map<String, String> env = [:]
        env.putAll(System.getenv())

        env.LC_ALL = "en_US.UTF-8"

        if (vars.options.LB3.value) {
            String lbHome = vars.options.LOGICBLOX_HOME.value
            env.LOGICBLOX_HOME = lbHome
            //We add these LB specific env vars here to make the server deployment more flexible (and the cli user's life easier)
            env.LB_PAGER_FORCE_START = "true"
            env.LB_MEM_NOWARN = "true"
            env.DOOP_HOME = Doop.doopHome

            //We add the following for pa-datalog to function properly (copied from the lib-env-bin.sh script)
            String path = env.PATH
            env.PATH = "${lbHome}/bin:${path ?: ""}" as String

            String ldLibraryPath = vars.options.LD_LIBRARY_PATH.value
            env.LD_LIBRARY_PATH = "${lbHome}/lib/cpp:${ldLibraryPath ?: ""}" as String
        }

        return env
    }

    /**
     * Returns a set of all the available analysis platforms
     *
     * @return the set of the available platforms
     */
    public static Set<String> availablePlatforms() {
        return availablePlatforms
    }

    /**
     * Given the platform id e.g., java_7, java_8, android_25 etc and platforms lib path,
     * it returns the set of (.jar) files associated with that platform
     *
     * @param platformID The platform id
     * @param platformsLib The platforms lib path, currently necessary since the method is static and platform libs path
     * is part of the analysis options
     * @return the set of jar files
     */
    public static Set<File> getArtifactsForPlatform(String platformID, String platformsLib) {
        def platformInfo = platformID.tokenize("_")
        if (platformInfo.size() < 2) {
            throw new RuntimeException("Invalid platform ${platformInfo}")
        }
        def version = platformInfo[1].toInteger()
        String path = "${platformsLib}/JREs/jre1.${version}/lib/"

        Set<File> files = []
        if (artifactsForPlatform.get(platformID) == null) {
            throw new RuntimeException("Unsupported platform: $platformID")
        }
        else {
            artifactsForPlatform.get(platformID).each({String fileName ->
                files.add(new File(path+fileName))
            })
        }

        return files
    }
}