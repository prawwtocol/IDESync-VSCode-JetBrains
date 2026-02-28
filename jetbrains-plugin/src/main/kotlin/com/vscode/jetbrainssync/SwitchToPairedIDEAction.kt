package com.vscode.jetbrainssync

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

private val LOG = Logger.getInstance(SwitchToPairedIDEAction::class.java)

class SwitchToPairedIDEAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        LOG.info("[CMD-I] SwitchToPairedIDEAction.actionPerformed called, project=${e.project != null}")
        
        val project = e.project ?: run {
            LOG.warn("[CMD-I] No project in AnActionEvent, returning")
            return
        }
        
        val service = project.service<VSCodeJetBrainsSyncService>()
        LOG.info("[CMD-I] Got service, calling switchToPairedIDE")
        service.switchToPairedIDE()
    }

    override fun update(e: AnActionEvent) {
        val hasProject = e.project != null
        e.presentation.isEnabledAndVisible = hasProject
    }
}
