package org.jetbrains.kotlin.tools.gradleimportcmd

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarterBase
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil.toCanonicalPath
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
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
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.plugins.gradle.settings.DefaultGradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

const val cmd = "importAndSave"

class GradleImportCmdMain : ApplicationStarterBase(cmd, 2) {
    private val LOG = Logger.getInstance("#org.jetbrains.kotlin.tools.gradleimportcmd.GradleImportCmdMain")

    override fun isHeadless(): Boolean = true

    override fun getUsageMessage(): String = "Usage: idea $cmd <path-to-gradle-project> <path-to-jdk>"

    override fun processCommand(args: Array<String>, currentDirectory: String?) {
        println("Initializing")

        System.setProperty("idea.skip.indices.initialization", "true")

        val application = ApplicationManagerEx.getApplicationEx()

        println("Get application")

        try {
            application.runReadAction {
                println("Entered readAction")
                try {
                    application.isSaveAllowed = true
                    println("Save allowed")
                    run()
                } catch (e: Exception) {
                    LOG.error(e)
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
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

    private fun run() {
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
            return
        }

        println("Project loaded, refreshing from Gradle...")

        val table = JavaAwareProjectJdkTableImpl.getInstanceEx()
        WriteAction.runAndWait<RuntimeException> {
            mySdk = (table.defaultSdkType as JavaSdk).createJdk("1.8", jdkPath)
            ProjectJdkTable.getInstance().addJdk(mySdk)
            ProjectRootManager.getInstance(project).projectSdk = mySdk
        }

        val projectSettings = GradleProjectSettings()
        projectSettings.externalProjectPath = projectPath
        projectSettings.withQualifiedModuleNames()

        val systemSettings = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
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

        println("Saving...")

        project.save()
        ProjectManagerEx.getInstanceEx().openProject(project)
        FileDocumentManager.getInstance().saveAllDocuments()
        ApplicationManager.getApplication().saveSettings()
        ApplicationManager.getApplication().saveAll()

        println("Done.")
        println("Setting delegation mode")
        DefaultGradleProjectSettings.getInstance(project).isDelegatedBuild = false
        println("Delegation setting delegation done")

        val finishedLautch = CountDownLatch(1)
        println("Compiling project")
        val callback = CompileStatusNotification { aborted, errors, warnings, compileContext -> run {
                println("Compilation done. Aborted=$aborted, Errors=$errors, Warnings=$warnings, CompileContext=$compileContext ")
                finishedLautch.countDown()
            }
        }
        CompilerManager.getInstance(project).make(callback)
        finishedLautch.await()
        println("Compile done. Exiting...")
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