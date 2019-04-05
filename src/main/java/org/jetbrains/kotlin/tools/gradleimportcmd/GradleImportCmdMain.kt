package org.jetbrains.kotlin.tools.gradleimportcmd

import com.intellij.compiler.CompilerConfigurationImpl
import com.intellij.compiler.CompilerWorkspaceConfiguration
import com.intellij.compiler.impl.CompileDriver2
import com.intellij.compiler.impl.ProjectCompileScope
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarterBase
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil.refreshProject
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.LowMemoryWatcher
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ThreeState
import com.intellij.util.containers.stream
import org.jetbrains.plugins.gradle.settings.DefaultGradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val cmd = "importAndSave"

class GradleImportCmdMain : ApplicationStarterBase(cmd, 2) {
    override fun isHeadless(): Boolean = true

    override fun getUsageMessage(): String = "Usage: idea $cmd <path-to-gradle-project> <path-to-jdk>"

    override fun processCommand(args: Array<String>, currentDirectory: String?) {
        println("Initializing")

        System.setProperty("idea.skip.indices.initialization", "true")

        var lowMemoryNotifier = LowMemoryWatcher.register({
            println("Low memory. Exiting...")
            System.exit(2)

        }, LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC)

        val application = ApplicationManagerEx.getApplicationEx()

        println("Get application")

        try {
            val project = application.runReadAction(Computable<Project?> {
                return@Computable try {
                    println("Entered readAction")
                    application.isSaveAllowed = true
                    println("Save allowed")
                    importProject()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            })

            println("Project = $project")
            val finishedLautch = CountDownLatch(1)
            if (project == null) {
                println("Project is null")
                System.exit(1)
            } else {
                println("Compiling project")
                var errorsCount: Int = 0
                var abortedStatus: Boolean = false
                val callback = CompileStatusNotification { aborted, errors, warnings, compileContext ->
                    run {
                        try {
                            errorsCount = errors
                            abortedStatus = aborted
                            println("--------------------------------")
                            println("Compilation done. Aborted=$aborted, Errors=$errors, Warnings=$warnings, CompileContext=$compileContext ")
                            CompilerMessageCategory.values().forEach { category ->
                                compileContext.getMessages(category).forEach {
                                    try {
                                        println("$category - ${it.virtualFile?.canonicalPath ?: "-"}: ${it.message}")
                                    } catch (e: Throwable) {
                                        e.printStackTrace()
                                    }

                                }
                            }
                            println("--------------------------------")

                        } finally {
                            finishedLautch.countDown()
                        }
                    }
                }

                CompilerConfigurationImpl.getInstance(project).setBuildProcessHeapSize(3500)

                if (System.getenv("build_mode_use_make") == "true") {
                    CompileDriver2(project).make(ProjectCompileScope(project), true, callback)
                } else {
                    val compileContext = CompileDriver2(project).rebuild(callback)

                    while (!finishedLautch.await(1, TimeUnit.MINUTES)) {
                        if (! compileContext.progressIndicator.isRunning) {
                            println("Progress indicator says that compilation is not running.")
                            break
                        }
                        println("Compilation status: Errors: ${compileContext.getMessages(CompilerMessageCategory.ERROR).size}. Warnings: ${compileContext.getMessages(CompilerMessageCategory.WARNING).size}.")
                    }
                }

                if (errorsCount > 0 || abortedStatus) {
                    println("Compilation has failed. Exiting...")
                } else {
                    println("Compile done. Exiting...")
                }

            }


        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            lowMemoryNotifier = null // low memory notifications are not required any more
            println("Exit application")
            application.exit(true, true)
        }
    }

    lateinit var projectPath: String
    lateinit var jdkPath: String

    override fun premain(args: Array<out String>) {
        if (args.size != 3) {
            printHelp()
        }

        projectPath = args[1]
        jdkPath = args[2]
        if (!File(projectPath).isDirectory) {
            println("$projectPath is not directory")
            printHelp()
        }
    }

    private fun importProject(): Project? {

        println("Opening project...")
        projectPath = projectPath.replace(File.separatorChar, '/')
        val vfsProject = LocalFileSystem.getInstance().findFileByPath(projectPath)
        if (vfsProject == null) {
            logError("Cannot find directory $projectPath")
            printHelp()
        }

        val project = ProjectUtil.openProject(projectPath, null, false)

        if (project == null) {
            logError("Unable to open project")
            gracefulExit(project)
            return null
        }

        DefaultGradleProjectSettings.getInstance(project).isDelegatedBuild = false

        println("Project loaded, refreshing from Gradle...")

        val table = JavaAwareProjectJdkTableImpl.getInstanceEx()
        WriteAction.runAndWait<RuntimeException> {
            val sdkType = JavaSdk.getInstance();
            mySdk = sdkType.createJdk("JDK_1.8", jdkPath, false)

            ProjectJdkTable.getInstance().addJdk(mySdk)
            ProjectRootManager.getInstance(project).projectSdk = mySdk
        }

        val projectSettings = GradleProjectSettings()
        projectSettings.externalProjectPath = projectPath
        projectSettings.delegatedBuild = ThreeState.NO
        projectSettings.storeProjectFilesExternally = ThreeState.NO
        projectSettings.withQualifiedModuleNames()

        val systemSettings = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
        val linkedSettings: Collection<out ExternalProjectSettings> = systemSettings.getLinkedProjectsSettings() as Collection<ExternalProjectSettings>
        linkedSettings.filter { it is GradleProjectSettings }.forEach { systemSettings.unlinkExternalProject(it.externalProjectPath) }

        systemSettings.linkProject(projectSettings)

        refreshProject(
                project,
                GradleConstants.SYSTEM_ID,
                projectPath,
                object : ExternalProjectRefreshCallback {
                    override fun onSuccess(externalProject: DataNode<ProjectData>?) {
                        if (externalProject != null) {
                            ServiceManager.getService(ProjectDataManager::class.java)
                                    .importData(externalProject, project, true)
                        } else {
                            println("Cannot get external project. See IDEA logs")
                            gracefulExit(project)
                        }
                    }
                },
                false,
                ProgressExecutionMode.MODAL_SYNC,
                true
        )

        println("Unloading buildSrc modules...")

        val moduleManager = ModuleManager.getInstance(project)
        val buildSrcModuleNames = moduleManager.sortedModules
                .filter { it.name.contains("buildSrc") }
                .map { it.name }
        moduleManager.setUnloadedModules(buildSrcModuleNames)

//        println("Setting delegation mode")
//        DefaultGradleProjectSettings.getInstance(project).isDelegatedBuild = false
//        println("Delegation setting delegation done")

        println("Saving...")

        project.save()
        ProjectManagerEx.getInstanceEx().openProject(project)
        FileDocumentManager.getInstance().saveAllDocuments()
        ApplicationManager.getApplication().saveSettings()
        ApplicationManager.getApplication().saveAll()

        println("Done.")

        return project
    }

    lateinit var mySdk: Sdk

    private fun gracefulExit(project: Project?) {
        if (project?.isDisposed == false) {
            ProjectUtil.closeAndDispose(project)
        }
        throw RuntimeException("Failed to proceed")
    }

    private fun logError(message: String) {
        System.err.println(message)
    }

    private fun printHelp() {
        println(usageMessage)
        System.exit(1)
    }
}