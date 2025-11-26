# Gradle NuGet Plugin

> **Note:** This is a fork of the original [gradle-nuget-plugin](https://github.com/Ullink/gradle-nuget-plugin) by Itiviti, updated for **Gradle 9 compatibility** and published under the `de.inetsoftware` group ID.

> **Disclaimer:** Most of the changes in this fork, including Gradle 8/9 compatibility fixes and cross-platform improvements, were created with the assistance of Cursor AI. While the code has been tested and verified, please review changes carefully before using in production environments.

## Fork Information

This fork maintains **forward compatibility** with the original plugin while providing:
- **Gradle 9+ compatibility** - Fixed Groovy API compatibility issues
- **Updated group ID** - Published as `de.inetsoftware.gradle:gradle-nuget-plugin`
- **Updated plugin IDs** - Use `de.inetsoftware.nuget` instead of `com.ullink.nuget`
- **All original functionality preserved** - Drop-in replacement for the original plugin

### Migration from Original Plugin

If you're using the original `com.ullink.gradle:gradle-nuget-plugin`, you can migrate to this fork by:

1. **Update your buildscript dependency:**
   ```groovy
   buildscript {
       dependencies {
           classpath 'de.inetsoftware.gradle:gradle-nuget-plugin:2.24'
       }
   }
   ```

2. **Update plugin application:**
   ```groovy
   apply plugin: 'de.inetsoftware.nuget'
   // or
   plugins {
       id 'de.inetsoftware.nuget' version '2.24'
   }
   ```

All task names and configuration remain the same - only the plugin ID and group ID have changed.

---

## Overview

This plugin allows to execute NuGet.exe from a gradle build.
It also supports pack & push commands through built-in tasks, nugetPack, nugetPush & nugetRestore.

## nugetPack

You can see this plugin being used for real on [il-repack](https://github.com/gluck/il-repack) project.
(together with msbuild one)

## nugetSpec

The task is to generate nuspec file by custom configuration.

Sample usage:

```groovy
buildscript {
    repositories {
      mavenCentral()
    }

    dependencies {
        classpath "de.inetsoftware.gradle:gradle-nuget-plugin:2.24"
    }
}

apply plugin: 'de.inetsoftware.nuget'

nuget {
    // nuget.exe version to use, defaults to 4.9.4

    // there are three different mutually excluded options for Nuget binary downloading:

    // first: define nuget version for download.
    // available versions can be found [here](https://dist.nuget.org/index.html)
    version = '4.9.4'

    // second - set nuget location, which will be used for download:
    nugetExePath = "https://dist.nuget.org/win-x86-commandline/latest/nuget.exe"

    // third: define nuget executable file, which is already downloaded previously:
    nugetExePath = "C:\\Tools\\Nuget\\nuget.exe"
}

nugetSpec {
    // Array, Map and Closure could be used to generate nuspec XML, for details please check NuGetSpecTest
    nuspec = [
        metadata: [
            title:          'project title',
            authors:        'Francois Valdy',
            // id:          default is project.name
            // version:     default is project.version
            // description: default is project.description
            // ...
        ]
        files: [
            { file (src: 'somefile1', target: 'tools') },
            { file (src: 'somefile2', target: 'tools') }
        ]
    ]
}
```

## nugetRestore

Nuget restore is used to retrieve missing packages before starting the build.

Sample usage:

```groovy
nugetRestore {
    solutionDirectory = path\to\project
    packagesDirectory = location\for\package\restore
}
```

Where
 - solutionDirectory - could either contain the .sln file or the repositories.config file
 - packagesDirectory - used only if a folder with repositories.config is used

## nugetSources

Nuget sources is used to add, remove, update, enable, disable nuget feeds.

Sample usage:

```groovy
nugetSources {
    operation = 'add'
    sourceName = 'localNuGetFeed'
    sourceUrl = 'http://foo.com'
    username = 'optional username'
    password = 'optional password'
    configFile = 'nuget.config'
}
```

Where
 - operation - could be add, remove, update, enable, disable and list
 - sourceName - name of the nuget feed
 - sourceUrl - url of the nuget feed
 - username - optional username for nuget sources that require http basic authentication
 - password - optional password for nuget sources that require http basic authentication
 - configFile - optional NuGet.config file to modify


# See also

[Gradle Msbuild plugin](https://github.com/Ullink/gradle-msbuild-plugin) - Allows to build VS projects & solutions.

[Gradle NUnit plugin](https://github.com/Ullink/gradle-nunit-plugin) - Allows to execute NUnit tests from CI (used with this plugin to build the projects prior to UT execution)

[Gradle OpenCover plugin](https://github.com/Ullink/gradle-opencover-plugin) - Allows to execute the UTs through OpenCover for coverage reports.

You can see these 4 plugins in use on [ILRepack](https://github.com/gluck/il-repack) project ([build.gradle](https://github.com/gluck/il-repack/blob/master/build.gradle)).

# Original Plugin

This is a fork of the original [gradle-nuget-plugin](https://github.com/Ullink/gradle-nuget-plugin) by Ullink/i-net software.

# License

All these plugins are licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html) with no warranty (expressed or implied) for any purpose.
