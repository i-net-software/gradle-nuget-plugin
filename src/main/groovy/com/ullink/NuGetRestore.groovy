package com.ullink

import com.ullink.util.GradleHelper
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory

import static org.apache.tools.ant.taskdefs.condition.Os.*

class NuGetRestore extends BaseNuGet {

    @Optional
    @InputFile
    File solutionFile
    @Optional
    @InputFile
    File packagesConfigFile

    @Input
    def sources = [] as Set
    @Input
    def noCache = false
    @Optional
    @InputFile
    File configFile
    @Input
    def requireConsent = false
    @Optional
    @InputDirectory
    File solutionDirectory
    @Input
    def disableParallelProcessing = false
    @Optional
    @Input
    def msBuildVersion
    @Optional
    @Input
    def msBuildPath
    @Optional
    @Input
    def packagesDirectory
    @Input
    def ignoreFailuresOnNonWindows = false
    @Input
    def useDotnetRestore = false  // Set to true to use 'dotnet restore' instead of 'nuget.exe' on non-Windows

    NuGetRestore() {
        super('restore')

        // Force always execute
        outputs.upToDateWhen { false }
    }

    void setSolutionFile(String path) {
        solutionFile = project.file(path)
    }

    void setPackagesConfigFile(String path) {
        packagesConfigFile = project.file(path)
    }

    void setConfigFile(String path) {
        configFile = project.file(path)
    }

    void setSolutionDirectory(String path) {
        solutionDirectory = project.file(path)
    }

    /**
     * @Deprecated Only provided for backward compatibility. Uses 'sources' instead
     */
    @Deprecated
    def setSource(String source) {
        sources.clear()
        sources.add(source)
    }

    @Override
    void exec() {
        project.logger.info("NuGetRestore.exec() called - solutionFile: ${solutionFile}, packagesConfigFile: ${packagesConfigFile}")
        
        // Convert File objects to absolute paths for proper argument handling
        // Normalize path separators for Windows compatibility
        if (packagesConfigFile) {
            def packagesPath = packagesConfigFile instanceof File ? packagesConfigFile.absolutePath : project.file(packagesConfigFile).absolutePath
            packagesPath = packagesPath.replace('/', File.separator)
            args packagesPath
            project.logger.info("Added packagesConfigFile to args: ${packagesPath}")
        }
        if (solutionFile) {
            def solutionPath = solutionFile instanceof File ? solutionFile.absolutePath : project.file(solutionFile).absolutePath
            solutionPath = solutionPath.replace('/', File.separator)
            args solutionPath
            project.logger.info("Added solutionFile to args: ${solutionPath}")
        } else {
            project.logger.warn("solutionFile is null or empty - cannot add to args")
        }

        if (!sources.isEmpty()) args '-Source', sources.join(';')
        if (noCache) args '-NoCache'
        if (configFile) args '-ConfigFile', configFile
        if (requireConsent) args '-RequireConsent'
        if (packagesDirectory) args '-PackagesDirectory', packagesDirectory
        if (solutionDirectory) args '-SolutionDirectory', solutionDirectory
        if (disableParallelProcessing) args '-DisableParallelProcessing'
        
        // On non-Windows platforms, skip MSBuildPath to avoid Mono/.NET SDK assembly loading issues
        // When nuget.exe runs under Mono, it cannot load .NET SDK assemblies (e.g., .NET 7.0's Microsoft.Build.dll)
        // So we skip -MSBuildPath on non-Windows to let NuGet use Mono's xbuild/MSBuild by default
        if (!isFamily(FAMILY_WINDOWS)) {
            // Skip MSBuildPath on non-Windows to avoid assembly loading errors
            // If useDotnetRestore=true, BaseNuGet will use 'dotnet restore' which handles MSBuild automatically
            project.logger.debug("Skipping MSBuildPath on non-Windows platform (NuGet will use Mono's xbuild/MSBuild)")
        } else {
            // On Windows, use MSBuildVersion as before
        if (!msBuildVersion) msBuildVersion = GradleHelper.getPropertyFromTask(project, 'version', 'msbuild')
        if (msBuildVersion) args '-MsBuildVersion', msBuildVersion
            
            // MSBuildPath can also be explicitly set on Windows
            if (msBuildPath) {
                args '-MSBuildPath', msBuildPath
            }
        }

        project.logger.info "Restoring NuGet packages " +
            (sources ? "from $sources" : '') +
            (packagesConfigFile ? "for packages.config ($packagesConfigFile)": '') +
            (solutionFile ? "for solution file ($solutionFile)" : '')
        super.exec()
    }

    @OutputDirectory
    File getPackagesFolder() {
        // https://docs.nuget.org/consume/command-line-reference#restore-command
        // If -PackagesDirectory <packagesDirectory> is specified, <packagesDirectory> is used as the packages directory.
        if (packagesDirectory) {
            return packagesDirectory
        }

        // If -SolutionDirectory <solutionDirectory> is specified, <solutionDirectory>\packages is used as the packages directory.
        // SolutionFile can also be provided.
        // Otherwise use '.\packages'
        def solutionDir = solutionFile ? project.file(solutionFile.getParent()) : solutionDirectory
        return new File(solutionDir ? solutionDir.toString() : '.', 'packages')
    }
    
    /**
     * Find the MSBuild.dll path in the dotnet SDK
     */
    private String findDotnetMsbuildPath(String dotnetPath) {
        try {
            // Get dotnet SDK path
            def process = [dotnetPath, '--info'].execute()
            process.waitFor()
            def output = process.text
            
            // Look for SDK base path
            def sdkBasePath = null
            output.eachLine { line ->
                if (line.contains('Base Path:') || line.contains('SDK Base Path:')) {
                    def path = line.split(':')[1]?.trim()
                    if (path) {
                        sdkBasePath = path
                    }
                }
            }
            
            if (sdkBasePath) {
                // Try to find MSBuild.dll in the SDK
                def msbuildDll = new File(sdkBasePath, 'MSBuild.dll')
                if (msbuildDll.exists()) {
                    return msbuildDll.parentFile.absolutePath
                }
                
                // Alternative: look in Current/MSBuild directory
                def currentMsbuild = new File(sdkBasePath, 'Current/MSBuild.dll')
                if (currentMsbuild.exists()) {
                    return currentMsbuild.parentFile.absolutePath
                }
            }
            
            // Fallback: try common SDK locations
            def commonPaths = [
                '/usr/local/share/dotnet/sdk',
                '/usr/share/dotnet/sdk',
                System.getProperty('user.home') + '/.dotnet/sdk'
            ]
            
            for (def basePath : commonPaths) {
                def sdkDir = new File(basePath)
                if (sdkDir.exists() && sdkDir.isDirectory()) {
                    // Find the highest version SDK
                    def sdkVersions = sdkDir.listFiles().findAll { it.isDirectory() && it.name.matches(/^\d+\.\d+\.\d+.*/) }
                    if (sdkVersions) {
                        sdkVersions.sort { a, b -> 
                            // Simple version comparison
                            def aVer = a.name.split(/[.-]/).collect { it.toInteger() }
                            def bVer = b.name.split(/[.-]/).collect { it.toInteger() }
                            return bVer <=> aVer
                        }
                        def latestSdk = sdkVersions[0]
                        def msbuildDll = new File(latestSdk, 'MSBuild.dll')
                        if (msbuildDll.exists()) {
                            return msbuildDll.parentFile.absolutePath
                        }
                    }
                }
            }
        } catch (Exception e) {
            project.logger.debug("Could not find dotnet MSBuild: ${e.message}")
        }
        return null
    }
}
