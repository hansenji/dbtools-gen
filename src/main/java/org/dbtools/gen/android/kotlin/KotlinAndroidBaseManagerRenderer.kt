/*
 * KotlinAndroidBaseRecordManager.kt
 *
 * Created on Nov 11, 2015
 *
 * Copyright 2015 Jeff Campbell. All rights reserved. Unauthorized reproduction
 * is a violation of applicable law. This material contains certain
 * confidential or proprietary information and trade secrets of Jeff Campbell.
 */
package org.dbtools.gen.android.kotlin


import org.dbtools.codegen.kotlin.KotlinClass
import org.dbtools.codegen.kotlin.KotlinVal
import org.dbtools.gen.AnnotationConsts
import org.dbtools.gen.GenConfig
import org.dbtools.gen.android.AndroidRecordRenderer
import org.dbtools.schema.schemafile.SchemaEntity
import org.dbtools.schema.schemafile.SchemaEntityType
import org.dbtools.schema.schemafile.SchemaTable

class KotlinAndroidBaseManagerRenderer(val genConfig: GenConfig) {
    private val myClass = KotlinClass()

    fun generate(entity: SchemaEntity, packageName: String) {
        val recordClassName = AndroidRecordRenderer.createClassName(entity)
        val className = getClassName(entity)
        myClass.apply {
            name = className
            this.packageName = packageName
            abstract = true
        }

        // header comment
        // Do not place date in file because it will cause a new check-in to scm        
        myClass.fileHeaderComment = "/*\n * $className.kt\n *\n * GENERATED FILE - DO NOT EDIT\n * \n */\n"

        // Since this is generated code.... suppress all warnings
        myClass.addAnnotation("@SuppressWarnings(\"all\")")

        // generate all of the main methods
        createManager(entity, packageName, recordClassName)

        if (genConfig.isEventBusSupport) {
            myClass.addImport("org.dbtools.android.domain.DBToolsEventBus")
            val busVariable = myClass.addVar("bus", "DBToolsEventBus")
            if (genConfig.isInjectionSupport) {
                myClass.addImport("javax.inject.Inject")
                busVariable.addAnnotation("Inject")
                busVariable.lateInit = true
            }
        }
    }

    private fun createManager(entity: SchemaEntity, packageName: String, recordClassName: String) {
        val recordConstClassName = "${recordClassName}Const"
        val type = entity.type

        var databaseManagerPackage = packageName.substring(0, packageName.lastIndexOf('.'))
        if (genConfig.isIncludeDatabaseNameInPackage) {
            databaseManagerPackage = databaseManagerPackage.substring(0, databaseManagerPackage.lastIndexOf('.'))
        }

        myClass.addImport(databaseManagerPackage + ".DatabaseManager")
        myClass.addImport("org.dbtools.android.domain.database.DatabaseWrapper")

        when (type) {
            SchemaEntityType.TABLE -> {
                val tableEntity = entity as SchemaTable
                if (tableEntity.isReadonly) {
                    myClass.addImport(if (genConfig.isRxJavaSupport) "org.dbtools.android.domain.RxAndroidBaseManagerReadOnly" else "org.dbtools.android.domain.AndroidBaseManagerReadOnly")
                    myClass.extends = if (genConfig.isRxJavaSupport) "RxAndroidBaseManagerReadOnly<$recordClassName>" else "AndroidBaseManagerReadOnly<$recordClassName>"
                } else {
                    myClass.addImport(if (genConfig.isRxJavaSupport) "org.dbtools.android.domain.RxAndroidBaseManagerWritable" else "org.dbtools.android.domain.AndroidBaseManagerWritable")
                    myClass.extends = if (genConfig.isRxJavaSupport) "RxAndroidBaseManagerWritable<$recordClassName>" else "AndroidBaseManagerWritable<$recordClassName>"
                }
            }
            SchemaEntityType.VIEW, SchemaEntityType.QUERY -> {
                myClass.addImport(if (genConfig.isRxJavaSupport) "org.dbtools.android.domain.RxAndroidBaseManagerReadOnly" else "org.dbtools.android.domain.AndroidBaseManagerReadOnly")
                myClass.extends = if (genConfig.isRxJavaSupport) "RxAndroidBaseManagerReadOnly<$recordClassName>" else "AndroidBaseManagerReadOnly<$recordClassName>"
            }
        }

        myClass.addFun("getDatabaseName", "String", content = "return $recordConstClassName.DATABASE").apply {
            isOverride = true
        }
        myClass.addFun("newRecord", recordClassName, content = "return $recordClassName()").apply {
            isOverride = true
        }

        if (type != SchemaEntityType.QUERY) {
            myClass.addFun("getTableName", "String", content = "return $recordConstClassName.TABLE").apply {
                isOverride = true
            }
        }

        myClass.addFun("getAllColumns", "Array<String>", content = "return $recordConstClassName.ALL_COLUMNS").apply {
            isOverride = true
        }

        val databaseNameParam = KotlinVal("databaseName", "String")
        if (genConfig.isJsr305Support) {
            databaseNameParam.addAnnotation(AnnotationConsts.NONNULL)
        }

        myClass.addFun("getReadableDatabase", "DatabaseWrapper<*>", listOf(databaseNameParam), "return databaseManager.getReadableDatabase(databaseName)").apply {
            isOverride = true
        }
        myClass.addFun("getReadableDatabase", "DatabaseWrapper<*>", content = "return databaseManager.getReadableDatabase(databaseName)")

        myClass.addFun("getWritableDatabase", "DatabaseWrapper<*>", listOf(databaseNameParam), "return databaseManager.getWritableDatabase(databaseName)").apply {
            isOverride = true
        }
        myClass.addFun("getWritableDatabase", "DatabaseWrapper<*>", content = "return databaseManager.getWritableDatabase(databaseName)")

        myClass.addFun("getAndroidDatabase", "org.dbtools.android.domain.AndroidDatabase?", listOf(databaseNameParam), "return databaseManager.getDatabase(databaseName)").apply {
            isOverride = true
        }

        myClass.addVar("databaseManager", "DatabaseManager")
        myClass.addConstructor(listOf(KotlinVal("databaseManager", "DatabaseManager")), "this.databaseManager = databaseManager").apply {
//            addAnnotation("javax.inject.Inject")
        }

        when (type) {
            SchemaEntityType.TABLE -> {
                myClass.addFun("getPrimaryKey", "String", content =  "return $recordConstClassName.PRIMARY_KEY_COLUMN").apply { isOverride = true }
                myClass.addFun("getDropSql", "String", content =  "return $recordConstClassName.DROP_TABLE").apply { isOverride = true }
                myClass.addFun("getCreateSql", "String", content =  "return $recordConstClassName.CREATE_TABLE").apply { isOverride = true }
            }
            SchemaEntityType.VIEW -> {
                myClass.addFun("getPrimaryKey", "String", content =  "return \"\"").apply { isOverride = true }
                myClass.addFun("getDropSql", "String", content =  "return $recordClassName.DROP_VIEW").apply { isOverride = true }
                myClass.addFun("getCreateSql", "String", content =  "return $recordClassName.CREATE_VIEW").apply { isOverride = true }
            }
            SchemaEntityType.QUERY -> {
                myClass.addFun("getQuery", "String").apply {
                    isAbstract = true
                }

                myClass.addFun("getTableName", "String", content =  "return getQuery()").apply { isOverride = true }
                myClass.addFun("getPrimaryKey", "String", content =  "return \"\"").apply { isOverride = true }
                myClass.addFun("getDropSql", "String", content =  "return \"\"").apply { isOverride = true }
                myClass.addFun("getCreateSql", "String", content =  "return \"\"").apply { isOverride = true }
            }
        }
    }


    fun writeToFile(outDir: String) {
        myClass.writeToDisk(outDir)
    }

    companion object {
        private val TAB = KotlinClass.tab

        fun getClassName(table: SchemaEntity): String {
            val recordClassName = AndroidRecordRenderer.createClassName(table)
            return recordClassName + "BaseManager"
        }
    }
}