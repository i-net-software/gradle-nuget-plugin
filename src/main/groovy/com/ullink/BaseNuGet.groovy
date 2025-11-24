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
    
    @TaskAction
    void execute() {
        // First, allow subclasses to configure args by calling their exec() method
        // This ensures solution files, packages.config, etc. are added to args
        // But we need to be careful to avoid recursion - only call if it's not BaseNuGet.exec()
        try {
            // Check if this class has an overridden exec() method (not the one from Exec)
            def execMethod = this.class.getDeclaredMethod("exec")
            if (execMethod != null && execMethod.declaringClass != Exec.class) {
                // This is a subclass's exec() method - call it to configure args
                execMethod.invoke(this)
            }
        } catch (NoSuchMethodException e) {
            // No overridden exec() method, that's fine
        }
        
        // Now configure executable and final args
        File localNuget = getNugetExeLocalPath()
        project.logger.debug "Using NuGet from path $localNuget.path"
        if (isFamily(FAMILY_WINDOWS)) {
            executable = localNuget.absolutePath
        } else {
            executable = "mono"
            // Get current args as a list and prepend nuget.exe path
            def currentArgs = getArgs().toList()
            setArgs([localNuget.absolutePath] + currentArgs)
        }
        args '-NonInteractive'
        args '-Verbosity', (verbosity ?: getNugetVerbosity())
        
        // Execute using the ExecActionFactory to avoid method interception recursion
        def execActionFactory = getExecActionFactory()
        if (execActionFactory != null) {
            def execAction = execActionFactory.newExecAction()
            execAction.setExecutable(executable)
            execAction.setArgs(getArgs())
            execAction.setWorkingDir(project.projectDir)
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
