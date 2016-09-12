/*
 * Copyright 2016-present Greg Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.intellij.clojure.tools

import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.console.IdeConsoleRootType
import com.intellij.execution.console.LanguageConsoleImpl
import com.intellij.execution.console.LanguageConsoleView
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.actions.CloseAction
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.ExceptionUtil
import com.intellij.util.containers.JBIterable
import com.intellij.util.text.nullize
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.nrepl.NReplClient
import org.intellij.clojure.nrepl.dumpObject
import org.intellij.clojure.psi.CForm
import org.intellij.clojure.psi.ClojureFile
import org.intellij.clojure.util.filter
import org.intellij.clojure.util.notNulls
import org.intellij.clojure.util.parents
import org.intellij.clojure.util.toIoFile
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * @author gregsh
 */
class ReplExecuteAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    val file = CommonDataKeys.PSI_FILE.getData(e.dataContext) as? ClojureFile
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext)
    e.presentation.isEnabledAndVisible = e.project != null && file != null && editor != null &&
        ScratchFileService.getInstance().getRootType(file.virtualFile) !is IdeConsoleRootType
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = CommonDataKeys.PSI_FILE.getData(e.dataContext) as? ClojureFile ?: return
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val text = if (editor.selectionModel.hasSelection()) {
      editor.selectionModel.getSelectedText(true) ?: ""
    }
    else {
      val text = editor.document.immutableCharSequence
      JBIterable.from(editor.caretModel.allCarets).transform {
        val elementAt = file.findElementAt(
            if (it.offset > 0 && (it.offset >= text.length || Character.isWhitespace(text[it.offset]))) it.offset - 1
            else it.offset)
        elementAt.parents().filter(CForm::class).last()
      }
          .notNulls()
          .reduce(StringBuilder()) { sb, o -> if (!sb.isEmpty()) sb.append("\n"); sb.append(o.text) }
          .toString()
    }
    executeInRepl(project, file, editor, text)
  }
}

class ReplActionPromoter : ActionPromoter {
  override fun promote(actions: MutableList<AnAction>, context: DataContext) =
      actions.find { it is ReplExecuteAction }?.let { listOf(it) } ?: null
}

val PROJECT_PATH_KEY: Key<String> = Key.create("")
fun executeInRepl(project: Project, file: ClojureFile, editor: Editor, text: String) {
  val projectDir = JBIterable.generate(PsiUtilCore.getVirtualFile(file)) { it.parent }.find {
    it.isDirectory && Tool.find(it.toIoFile()) != null
  }
  val executionManager = ExecutionManager.getInstance(project)
  val existing = executionManager.contentManager.allDescriptors.run {
    find { (it.executionConsole as? LanguageConsoleView)?.virtualFile == PsiUtilCore.getVirtualFile(file)} ?:
    find { it.processHandler.let { it != null && !it.isProcessTerminated && (projectDir?.path ?: "") == it.getUserData(PROJECT_PATH_KEY) } }
  }
  val descriptor = existing ?: startNewProcess(project, projectDir) ?: return
  if (descriptor.processHandler.let { it == null || it.isProcessTerminated || it.isProcessTerminating }) {
    ExecutionUtil.restart(descriptor)
  }
  val consoleView = descriptor.executionConsole as LanguageConsoleView
  val textAdjusted = if (editor == consoleView.consoleEditor) {
    editor.selectionModel.setSelection(0, editor.document.textLength); editor.document.text
  }
  else text
  descriptor.attachedContent?.run { manager.setSelectedContent(this) }
  (descriptor.processHandler as ClojureProcessHandler).sendCommand(textAdjusted)
}

fun startNewProcess(project: Project, projectDir: VirtualFile?): RunContentDescriptor? {
  val executor = DefaultRunExecutor.getRunExecutorInstance()
  val title = if (projectDir == null) "Clojure REPL"
  else ProjectFileIndex.SERVICE.getInstance(project).getContentRootForFile(projectDir).let {
    if (it == null || it == projectDir) "[${projectDir.name}] REPL" else "[${VfsUtil.getRelativePath(projectDir, it)}] REPL"
  }
  val consoleView = LanguageConsoleImpl(project, title, ClojureLanguage)
  consoleView.consoleEditor.setFile(consoleView.virtualFile)

  val workingDir = (projectDir ?: project.baseDir).toIoFile()
  val tool = Tool.find(workingDir) ?: Lein
  val processHandler = tool.runRepl(workingDir.path) { cmd -> ClojureProcessHandler(consoleView, cmd) }
  processHandler.putUserData(PROJECT_PATH_KEY, projectDir?.path ?: "")

  val toolbarActions = DefaultActionGroup()
  val panel = JPanel(BorderLayout())
  panel.add(consoleView.component, BorderLayout.CENTER)
  panel.add(ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, toolbarActions, false).component, BorderLayout.WEST)

  val descriptor = RunContentDescriptor(consoleView, processHandler, panel, consoleView.title)
  Disposer.register(descriptor, consoleView)
  Disposer.register(descriptor, Disposable { processHandler.destroyProcess() })

  toolbarActions.add(CloseAction(executor, descriptor, project))
  consoleView.createConsoleActions().forEach { action -> toolbarActions.add(action) }

  ExecutionManager.getInstance(project).contentManager.showRunContent(executor, descriptor)
  ToolWindowManager.getInstance(project).getToolWindow(executor.id).activate(null, false, false)

  processHandler.startNotify()
  return descriptor
}

class ClojureProcessHandler(val consoleView: LanguageConsoleView, cmd: GeneralCommandLine) : OSProcessHandler(cmd) {

  var repl: NReplClient? = null
  val whenConnected = ActionCallback()

  init {
    ProcessTerminatedListener.attach(this)
    consoleView.attachToProcess(this)
    consoleView.prompt = null
  }

  override fun isSilentlyDestroyOnClose() = false

  override fun notifyProcessTerminated(exitCode: Int) {
    consoleView.isEditable = false
    repl?.disconnect()
    super.notifyProcessTerminated(exitCode)
  }

  override fun notifyTextAvailable(text: String, outputType: Key<*>) {
    when {
      repl == null && text.startsWith("nREPL server started on port ") -> {
        repl = super.notifyTextAvailable(text, outputType).run { createNRepl(text) }
        if (repl!!.valid) whenConnected.setDone() else whenConnected.setRejected()
      }
      else -> super.notifyTextAvailable(text, outputType)
    }
  }

  fun sendCommand(text: String) = whenConnected.doWhenDone {
    val trimmed = text.trim()
    consoleView.print("\n" + (consoleView.prompt ?: ""), ConsoleViewContentType.NORMAL_OUTPUT)
    ConsoleViewUtil.printAsFileType(consoleView, trimmed, ClojureFileType)
    (consoleView as ConsoleViewImpl).requestScrollingToEnd()
    val eval = repl!!.eval(trimmed)
    eval["ns"]?.let { consoleView.prompt = "$it=> " }
    val result = eval["value"]
    val error = "${eval["ex"]?.let { "$it\n" } ?: ""}${eval["err"] ?: ""}${eval["out"] ?: ""}".nullize()
    result?.let {
      consoleView.print("\n${dumpObject(it)}", ConsoleViewContentType.NORMAL_OUTPUT)
    }
    error?.let { msg ->
       val adjusted = msg.indexOf(", compiling:(").let { if (it == -1) msg else msg.substring(0, it) + "\n" + msg.substring(it + 2) }
      consoleView.print("\n$adjusted", ConsoleViewContentType.ERROR_OUTPUT)
    }
    if (result == null && error == null) {
      consoleView.print("\n${dumpObject(eval)}", ConsoleViewContentType.NORMAL_OUTPUT)
    }
    consoleView.requestScrollingToEnd()
  }

  private fun createNRepl(nReplStarted: String): NReplClient {
    val matcher = "port (\\S+).* host (\\S+)".toRegex().find(nReplStarted) ?: return NReplClient("", -1)
    val repl = NReplClient(matcher.groupValues[2], StringUtil.parseInt(matcher.groupValues[1], -1))
    try {
      repl.connect()
      val info = repl.describeSession()
      consoleView.prompt = ((info["aux"] as? Map<*, *>)?.get("current-ns") as? String)?.let { "$it=> "} ?: "=> "
      consoleView.print("target info:" + dumpObject(info) + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
      repl.eval("(when (clojure.core/resolve 'clojure.main/repl-requires)" +
          " (clojure.core/map clojure.core/require clojure.main/repl-requires))")
    }
    catch(e: Exception) {
      consoleView.print(ExceptionUtil.getThrowableText(e) + "\n", ConsoleViewContentType.ERROR_OUTPUT)
    }
    return repl
  }

}
