package com.github.lukasschneider.optionalize.services

import com.intellij.openapi.project.Project
import com.github.lukasschneider.optionalize.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
