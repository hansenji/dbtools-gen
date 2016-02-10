/*
 * JPAJSERecordManager.java
 *
 * Created on February 24, 2007, 11:03 AM
 *
 * Copyright 2007 Jeff Campbell. All rights reserved. Unauthorized reproduction 
 * is a violation of applicable law. This material contains certain 
 * confidential or proprietary information and trade secrets of Jeff Campbell.
 */
package org.dbtools.gen.jpa;

import org.dbtools.codegen.java.Access;
import org.dbtools.codegen.java.JavaClass;
import org.dbtools.codegen.java.JavaVariable;
import org.dbtools.gen.GenConfig;
import org.dbtools.schema.schemafile.SchemaEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author Jeff
 */
public class JPARecordManagerRenderer {

    private JavaClass myClass;
    private GenConfig genConfig;

    /**
     * Creates a new instance of JPARecordManagerRenderer.
     */
    public JPARecordManagerRenderer() {
    }

    public void generateObjectCode(SchemaEntity entity, String packageName) {
        String className = getClassName(entity);
        myClass = new JavaClass(packageName, className);
        myClass.setExtends(JPABaseRecordManagerRenderer.getClassName(entity)); // extend the generated base class

        // Injection support
        if (genConfig.isJavaeeSupport()) {
            myClass.addImport("javax.inject.Named");
            myClass.addAnnotation("Named");
        } else if (genConfig.isInjectionSupport()) {
            myClass.addAnnotation("javax.inject.Singleton");
        }

        // header comment
        Date now = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss");
        String fileHeaderComment;
        fileHeaderComment = "/*\n";
        fileHeaderComment += " * " + className + ".java\n";
        fileHeaderComment += " *\n";
        fileHeaderComment += " * Generated on: " + dateFormat.format(now) + "\n";
        fileHeaderComment += " *\n";
        fileHeaderComment += " */\n";
        myClass.setFileHeaderComment(fileHeaderComment);

        // constructor
        myClass.setCreateDefaultConstructor(false);

        if (genConfig.isInjectionSupport()) {
            if (!genConfig.isJavaeeSupport()) {
                myClass.addConstructor(Access.PUBLIC, null, null).addAnnotation("javax.inject.Inject");
            }
        } else {
            List<JavaVariable> constParams = new ArrayList<>();
            myClass.addImport("javax.persistence.EntityManager");
            constParams.add(new JavaVariable("EntityManager", "em"));
            String constContent = "super(em);";
            myClass.addConstructor(Access.PUBLIC, constParams, constContent);
        }
    }

    public static String getClassName(SchemaEntity entity) {
        String recordClassName = JPARecordClassRenderer.createClassName(entity);
        return recordClassName + "Manager";
    }

    public void writeToFile(String outDir) {
        myClass.writeToDisk(outDir);
    }

    public void setGenConfig(GenConfig genConfig) {
        this.genConfig = genConfig;
    }
}
