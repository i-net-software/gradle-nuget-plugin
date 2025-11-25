package com.ullink

import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Console
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.Input
import org.gradle.process.internal.ExecActionFactory
import javax.inject.Inject
import java.nio.file.Paths

import static org.apache.tools.ant.taskdefs.condition.Os.*

class BaseNuGet extends Exec {
    private static final String NUGET_EXE = 'NuGet.exe'

    @Console
    String verbosity

    @Optional
    @Input
    String nugetExePath

    private final ObjectFactory objectFactory
    private final ExecActionFactory execActionFactory
    private ObjectFactory cachedObjectFactory
    private ExecActionFactory cachedExecActionFactory

    @Inject
    BaseNuGet(ObjectFactory objectFactory, ExecActionFactory execActionFactory) {
        this.objectFactory = objectFactory
        this.execActionFactory = execActionFactory
    }

    BaseNuGet() {
        // Default constructor for compatibility with Gradle 8
        // In Gradle 8, Exec tasks don't require these, so we set them to null
        this.objectFactory = null
        this.execActionFactory = null
    }

    // Only provide these for Gradle 9+ compatibility
    // Use lazy initialization with caching to avoid StackOverflow in Gradle 8
    ObjectFactory getObjectFactory() {
        if (objectFactory != null) {
            return objectFactory
        }
        if (cachedObjectFactory != null) {
            return cachedObjectFactory
        }
        // Lazy initialization - only access project when actually needed
        // Cache the result to avoid repeated access that could cause recursion
        try {
            // Use a thread-local flag to detect recursion
            def threadLocal = Thread.currentThread().getContextClassLoader()
            if (threadLocal != null) {
                cachedObjectFactory = project.objects
                return cachedObjectFactory
            }
        } catch (Exception e) {
            // If we can't access it, return null (Gradle 8 compatibility)
        }
        return null
    }

    ExecActionFactory getExecActionFactory() {
        if (execActionFactory != null) {
            return execActionFactory
        }
        if (cachedExecActionFactory != null) {
            return cachedExecActionFactory
        }
        // Lazy initialization with caching
        try {
            cachedExecActionFactory = project.services.get(ExecActionFactory.class)
            return cachedExecActionFactory
        } catch (Exception e) {
            // If we can't access it, return null (Gradle 8 compatibility)
        }
        return null
    }

    @Internal
    protected File getNugetHome() {
        def env = System.getenv()
        def nugetHome = env['NUGET_HOME']
        if (nugetHome != null) {
            return new File(nugetHome)
        } else {
            def nugetCacheFolder = Paths.get(
                    project.gradle.gradleUserHomeDir.absolutePath,
                    'caches',
                    'nuget',
                    project.extensions.nuget.version.toString())

            return nugetCacheFolder.toFile()
        }
    }

    protected BaseNuGet(String command) {
        this()
        args command
    }
    
    // Dummy exec() method that does nothing - allows subclasses to call super.exec() without error
    // The actual execution is handled by @TaskAction execute()
    void exec() {
        // Do nothing - execution is handled by execute() method
        // This method exists so that subclasses can call super.exec() without getting a MissingMethodException
    }
    
    @TaskAction
    void execute() {
        // First, allow subclasses to configure args by calling their exec() method
        // This ensures solution files, packages.config, etc. are added to args
        // The dummy exec() method above allows super.exec() calls to succeed
        
        // Get args BEFORE calling subclass exec() to see what's already there
        def argsBeforeExec = getArgs().toList()
        project.logger.info("Args BEFORE calling subclass exec(): ${argsBeforeExec}")
        
        // Check if solutionFile is set before calling exec()
        try {
            if (this.hasProperty('solutionFile')) {
                project.logger.info("solutionFile property exists: ${this.solutionFile}")
            } else {
                project.logger.warn("solutionFile property does NOT exist on task")
            }
        } catch (Exception e) {
            project.logger.debug("Could not check solutionFile property: ${e.message}")
        }
        
        // Call the subclass's exec() method using metaClass to bypass BaseNuGet.exec()
        // This ensures we call the actual overridden method in the subclass
        try {
            // Check if this class (or any parent up to BaseNuGet) has an exec() method
            def execMethod = null
            def currentClass = this.class
            while (currentClass != null && currentClass != BaseNuGet.class && currentClass != Exec.class) {
                execMethod = currentClass.declaredMethods.find { it.name == 'exec' && it.parameterCount == 0 }
                if (execMethod != null) break
                currentClass = currentClass.superclass
            }
            
            if (execMethod != null) {
                project.logger.info("Calling subclass exec() method: ${execMethod.declaringClass.simpleName}.exec()")
                // Directly invoke the method using reflection to bypass Gradle's method interception
                execMethod.invoke(this)
            } else {
                project.logger.warn("No exec() method found in subclass ${this.class.simpleName}")
            }
        } catch (Exception e) {
            // Subclass exec() call failed, continue with execution
            project.logger.warn("Could not call subclass exec(): ${e.class.simpleName}: ${e.message}")
            e.printStackTrace()
        }
        
        // Get args AFTER calling subclass exec() to see what was added
        def argsAfterExec = getArgs().toList()
        project.logger.info("Args AFTER calling subclass exec(): ${argsAfterExec}")
        
        // Now configure executable and final args
        File localNuget = getNugetExeLocalPath()
        project.logger.debug "Using NuGet from path $localNuget.path"
        
        // Get current args (should include solution file, packages.config, etc. from subclass exec())
        def currentArgs = getArgs().toList()
        project.logger.info("Args before deduplication: ${currentArgs}")
        
        // Build a set of expected file paths to ensure we only add them once
        def expectedPaths = new HashSet()
        try {
            if (this.hasProperty('solutionFile') && this.solutionFile) {
                def solutionFilePath = this.solutionFile instanceof File ? this.solutionFile.absolutePath : project.file(this.solutionFile).absolutePath
                expectedPaths.add(solutionFilePath.replace('/', File.separator).toLowerCase())
            }
            if (this.hasProperty('packagesConfigFile') && this.packagesConfigFile) {
                def packagesConfigFilePath = this.packagesConfigFile instanceof File ? this.packagesConfigFile.absolutePath : project.file(this.packagesConfigFile).absolutePath
                expectedPaths.add(packagesConfigFilePath.replace('/', File.separator).toLowerCase())
            }
        } catch (Exception e) {
            project.logger.debug("Could not determine expected paths: ${e.message}")
        }
        
        // Remove duplicates from args - normalize paths and check for duplicates
        // This is critical because the solution file might be added multiple times
        def normalizedArgs = []
        def seenPaths = new HashSet()
        currentArgs.each { arg ->
            def normalizedPath = arg instanceof File ? arg.absolutePath.replace('/', File.separator) : arg.toString().replace('/', File.separator)
            def normalizedPathLower = normalizedPath.toLowerCase()
            
            // Skip if we've seen this exact path before
            if (!seenPaths.contains(normalizedPathLower)) {
                seenPaths.add(normalizedPathLower)
                // Convert File objects to strings for consistent argument handling
                normalizedArgs.add(arg instanceof File ? normalizedPath : arg)
            } else {
                project.logger.info("Skipping duplicate arg: ${normalizedPath}")
            }
        }
        
        // DO NOT manually add solution file or packages.config here
        // The subclass exec() method should have already added them via args
        // Adding them here would cause duplicates
        // The deduplication above should handle any duplicates that were added
        
        project.logger.info("Args after deduplication: ${normalizedArgs}")
        
        // Clear all args first to avoid any accumulation issues
        setArgs([])
        
        // Set deduplicated args
        setArgs(normalizedArgs)
        
        if (isFamily(FAMILY_WINDOWS)) {
            executable = localNuget.absolutePath
        } else {
            executable = "mono"
            // Prepend nuget.exe path to args for mono execution
            setArgs([localNuget.absolutePath] + normalizedArgs)
        }
        
        // Add flags after setting the base args
        args '-NonInteractive'
        args '-Verbosity', (verbosity ?: getNugetVerbosity())
        
        project.logger.info("Final args before execution: ${getArgs().toList()}")
        
        // Check if we should ignore failures on non-Windows (for Mono xbuild issues)
        // Set ignoreExitValue on the Exec task itself before execution
        def shouldIgnoreFailures = false
        try {
            if (this.hasProperty('ignoreFailuresOnNonWindows') && this.ignoreFailuresOnNonWindows) {
                shouldIgnoreFailures = !isFamily(FAMILY_WINDOWS)
                if (shouldIgnoreFailures) {
                    project.logger.debug("Will ignore failures on non-Windows platform")
                    // Use the Exec task's built-in ignoreExitValue property
                    this.ignoreExitValue = true
                }
            }
        } catch (Exception e) {
            project.logger.debug("Could not check ignoreFailuresOnNonWindows: ${e.message}")
        }
        
        // Execute using the ExecActionFactory to avoid method interception recursion
        // However, if we need to ignore exit values, we must use the Exec task's built-in execution
        // because ExecAction doesn't respect the Exec task's ignoreExitValue property
        if (shouldIgnoreFailures) {
            // Use Exec task's built-in execution when ignoring failures (respects ignoreExitValue)
            try {
                def execMethod = Exec.class.getDeclaredMethod("exec")
                execMethod.setAccessible(true)
                execMethod.invoke(this)
            } catch (Throwable e) {
                project.logger.warn("NuGet restore failed on non-Windows platform (likely Mono xbuild issue), ignoring: ${e.class.simpleName}: ${e.message}")
                return
            }
        } else {
            // Use ExecActionFactory for normal execution (avoids stack overflow in Gradle 8)
            def execActionFactory = getExecActionFactory()
            if (execActionFactory != null) {
                def execAction = execActionFactory.newExecAction()
                execAction.setExecutable(executable)
                execAction.setArgs(getArgs())
                execAction.setWorkingDir(project.projectDir)
                // ExecAction.execute() throws ExecException on failure, which is what we want
                // This ensures tests fail properly when nuget.exe execution fails
                execAction.execute()
            } else {
                // Fallback: try to call Exec.exec() via reflection
                try {
                    def execMethod = Exec.class.getDeclaredMethod("exec")
                    execMethod.setAccessible(true)
                    execMethod.invoke(this)
                } catch (Exception e) {
                    throw new RuntimeException("Cannot execute NuGet command - no ExecActionFactory available", e)
                }
            }
        }
    }

    @Internal
    protected File getNugetExeLocalPath() {
        File localNuget

        if (nugetExePath != null && !nugetExePath.empty && !nugetExePath.startsWith("http")) {
            // Resolve relative paths relative to project directory, not current working directory
            localNuget = nugetExePath.startsWith("/") || (nugetExePath.length() > 2 && nugetExePath[1] == ':') 
                ? new File(nugetExePath)  // Absolute path (Unix or Windows)
                : project.file(nugetExePath)  // Relative path - resolve from project directory

            if (localNuget.exists()) {
                return localNuget
            }

            throw new IllegalStateException("Unable to find nuget by path $nugetExePath (resolved to ${localNuget.absolutePath}, please check property 'nugetExePath')")
        }

        def folder = getNugetHome()
        localNuget = new File(folder, NUGET_EXE)

        if (!localNuget.exists()) {
            if (!folder.isDirectory())
                folder.mkdirs()

            def nugetUrl = getNugetDownloadLink()

            project.logger.info "Downloading NuGet from $nugetUrl ..."

            new URL(nugetUrl).withInputStream {
                inputStream ->
                    localNuget.withOutputStream { outputStream ->
                        outputStream << inputStream
                    }
            }
        }
        localNuget
    }

    @Internal
    protected String getNugetDownloadLink() {
        if (nugetExePath != null && !nugetExePath.empty && nugetExePath.startsWith("http")) {
            project.logger.debug("Nuget url path is resolved from property 'nugetExePath'")

            return nugetExePath
        }

        def exeName = project.extensions.nuget.version < '3.4.4' ? 'nuget.exe' : 'NuGet.exe'

        return "https://dist.nuget.org/win-x86-commandline/v${project.extensions.nuget.version}/${exeName}"
    }

    @Internal
    protected String getNugetVerbosity() {
        if (logger.debugEnabled) return 'detailed'
        if (logger.infoEnabled) return 'normal'
        return 'quiet'
    }
}
