/*
 * AndroidBaseRecordClassRenderer.java
 *
 * Created on Sep 9, 2010
 *
 * Copyright 2010 Jeff Campbell. All rights reserved. Unauthorized reproduction
 * is a violation of applicable law. This material contains certain
 * confidential or proprietary information and trade secrets of Jeff Campbell.
 */
package org.dbtools.gen.android;

import org.dbtools.codegen.*;
import org.dbtools.schema.ClassInfo;
import org.dbtools.renderer.SchemaRenderer;
import org.dbtools.renderer.SqliteRenderer;
import org.dbtools.schema.dbmappings.DatabaseMapping;
import org.dbtools.schema.schemafile.*;

import java.util.*;

/**
 * @author Jeff
 */
public class AndroidBaseRecordClassRenderer {

    private JavaClass myClass;
    private JavaClass myTestClass;
    private boolean writeTestClass = true;
    private List<JavaEnum> enumerationClasses = new ArrayList<>();
    private StringBuilder toStringContent;
    private StringBuilder cleanupOrphansContent;
    private boolean useInnerEnums = true;
    public static final String CLEANUP_ORPHANS_METHOD_NAME = "cleanupOrphans";
    private static final String ALL_KEYS_VAR_NAME = "ALL_KEYS";
    private boolean useLegacyJUnit = false;

    private boolean injectionSupport = false;
    private boolean dateTimeSupport = false; // use datetime or jsr 310

    public static final String PRIMARY_KEY_COLUMN = "PRIMARY_KEY_COLUMN";

    /**
     * Creates a new instance of AndroidBaseRecordClassRenderer.
     */
    public AndroidBaseRecordClassRenderer() {
    }

    public void generate(SchemaDatabase database, SchemaTable table, String packageName) {
        String className = createClassName(table);

        // SQLite table field types
        DatabaseMapping databaseMapping = SchemaRenderer.readXMLTypes(this.getClass(), SchemaRenderer.DEFAULT_TYPE_MAPPING_FILENAME, "sqlite");

        if (table.isEnumerationTable()) {
            String enumClassName = createClassName(table);
            List<TableEnum> enums = table.getTableEnums();

            myClass = new JavaEnum(packageName, enumClassName, table.getTableEnumsText());
            myClass.setCreateDefaultConstructor(false);
            writeTestClass = false;

            if (enums.size() > 0) {
                myClass.addImport("java.util.Map");
                myClass.addImport("java.util.EnumMap");
                JavaVariable enumStringMapVar = myClass.addVariable("Map<" + enumClassName + ", String>", "enumStringMap",
                        "new EnumMap<" + enumClassName + ", String>(" + enumClassName + ".class)");
                enumStringMapVar.setStatic(true);

                myClass.addImport("java.util.List");
                myClass.addImport("java.util.ArrayList");
                JavaVariable stringListVar = myClass.addVariable("List<String>", "stringList", "new ArrayList<String>()");
                stringListVar.setStatic(true);

                for (TableEnum enumItem : enums) {
                    myClass.appendStaticInitializer("enumStringMap.put(" + enumItem.getName() + ", \"" + enumItem.getValue() + "\");");
                    myClass.appendStaticInitializer("stringList.add(\"" + enumItem.getValue() + "\");");
                    myClass.appendStaticInitializer("");
                }

                List<JavaVariable> getStringMParam = new ArrayList<>();
                getStringMParam.add(new JavaVariable(enumClassName, "key"));
                JavaMethod getStringM = myClass.addMethod(Access.PUBLIC, "String", "getString", getStringMParam, "return enumStringMap.get(key);");
                getStringM.setStatic(true);

                myClass.addImport("java.util.Collections");
                JavaMethod getListM = myClass.addMethod(Access.PUBLIC, "List<String>", "getList", "return Collections.unmodifiableList(stringList);");
                getListM.setStatic(true);
            }
        } else {
            myClass = new JavaClass(packageName, className);
            myClass.addImport(packageName.substring(0, packageName.lastIndexOf('.')) + ".BaseRecord");
            myClass.setExtends("BaseRecord");

            writeTestClass = true;
        }

        myTestClass = new JavaClass(packageName, className + "Test");
        initTestClass();

        // prep
        toStringContent = new StringBuilder();
        toStringContent.append("String text = \"\\n\";\n");
        cleanupOrphansContent = new StringBuilder();

        // header comment
        // Do not place date in file because it will cause a new check-in to scm        
        String fileHeaderComment;
        fileHeaderComment = "/*\n";
        fileHeaderComment += " * " + className + ".java\n";
        fileHeaderComment += " *\n";
        fileHeaderComment += " * GENERATED FILE - DO NOT EDIT\n";
        fileHeaderComment += " * CHECKSTYLE:OFF\n";
        fileHeaderComment += " * \n";
        fileHeaderComment += " */\n";
        myClass.setFileHeaderComment(fileHeaderComment);

        // Since this is generated code.... suppress all warnings
        myClass.addAnnotation("@SuppressWarnings(\"all\")");

        final String TAB = JavaClass.getTab();

        boolean primaryKeyAdded = false;

        // constants and variables
        String databaseName = database.getName();
        myClass.addConstant("String", "DATABASE", databaseName);

        String tableName = table.getName();
        myClass.addConstant("String", "TABLE", tableName);
        myClass.addConstant("String", "FULL_TABLE", databaseName + "." + tableName);

        if (!myClass.isEnum()) {
            myClass.addMethod(Access.PUBLIC, "String", "getDatabaseName", "return DATABASE;").addAnnotation("Override");
            myClass.addMethod(Access.PUBLIC, "String", "getTableName", "return TABLE;").addAnnotation("Override");
        }

        // post field method content
        String contentValuesContent = "ContentValues values = new ContentValues();\n";
        String setContentValuesContent = "";
        String setContentCursorContent = "";

        List<SchemaTableField> fields = table.getFields();
        List<String> keys = new ArrayList<>();
        for (SchemaTableField field : fields) {
            boolean primaryKey = field.isPrimaryKey();

            String fieldName = field.getName();

            // override default name
            String fieldNameJavaStyle = field.getName(true);

            // check for second primary key
            if (primaryKey && primaryKeyAdded) {
                throw new IllegalStateException("Cannot have more than 1 Primary Key [" + fieldNameJavaStyle + "]");
            } else {
                primaryKeyAdded = true;
            }

            // constants
            String constName = JavaClass.formatConstant(fieldNameJavaStyle);
            String fieldKey = "C_" + constName;
            keys.add(fieldKey);

            if (primaryKey) {
                myClass.addConstant("String", PRIMARY_KEY_COLUMN, fieldName); // add a reference to this column
            }

            myClass.addConstant("String", fieldKey, fieldName);
            myClass.addConstant("String", "FULL_C_" + constName, tableName + "." + fieldName);

            // skip some types of variables at this point (so that we still get the column name and the property name)
            switch (field.getForeignKeyType()) {
                case MANYTOONE:
                    generateManyToOne(database, packageName, field);
                    continue;
                case ONETOMANY:
                    generateOneToMany(database, packageName, field);
                    continue;
                case ONETOONE:
                    generateOneToOne(database, packageName, field);
                    continue;
                default:
            }

            createToStringMethodContent(field, fieldNameJavaStyle);

            // creates the variable OR changes the var to an enum
            JavaVariable newVariable;
            if (field.isEnumeration()) {
                newVariable = generateEnumeration(field, fieldNameJavaStyle, packageName, database);
            } else {
                newVariable = generateFieldVariable(fieldNameJavaStyle, field);
            }

            // Primary key / not enum methods
            if (primaryKey && !myClass.isEnum()) {
                myClass.addMethod(Access.PUBLIC, "String", "getRowIDKey", "return " + fieldKey + ";").addAnnotation("Override");

                // add vanilla getPrimaryKeyID() / setPrimaryKeyID(...) for the primary key
                myClass.addMethod(Access.PUBLIC, field.getJavaTypeText(), "getPrimaryKeyID", "return " + fieldNameJavaStyle + ";").addAnnotation("Override");

                List<JavaVariable> setIDParams = new ArrayList<>();
                setIDParams.add(new JavaVariable(newVariable.getDataType(), "id"));
                myClass.addMethod(Access.PUBLIC, "void", "setPrimaryKeyID", setIDParams, "this." + fieldNameJavaStyle + " = id;").addAnnotation("Override");
            }


            if (!myClass.isEnum()) {
                myClass.addVariable(newVariable);
            }

            // method values
            if (!(primaryKey && field.isIncrement())) {
                String value = fieldNameJavaStyle;
                SchemaFieldType fieldType = field.getJdbcDataType();
                if (field.isEnumeration()) {
                    value = newVariable.getName() + ".ordinal()";
                } else if (fieldType == SchemaFieldType.DATE || fieldType == SchemaFieldType.TIMESTAMP || fieldType == SchemaFieldType.TIME) {
                    if (!dateTimeSupport) {
                        String methodName = dateTimeSupport ? "dateTimeToDBString" : "dateToDBString";
                        value = methodName + "(" + fieldNameJavaStyle + ")";
                    } else {
                        String getTimeMethod = dateTimeSupport ? ".getMillis()" : ".getTime()";

                        value = fieldNameJavaStyle + " != null ? " + fieldNameJavaStyle + getTimeMethod + " : null";
                    }
                }
                contentValuesContent += "values.put(" + fieldKey + ", " + value + ");\n";

                setContentValuesContent += fieldNameJavaStyle + " = " + getContentValuesGetterMethod(field, fieldKey, newVariable) + ";\n";
            }

            setContentCursorContent += fieldNameJavaStyle + " = " + getContentValuesCursorGetterMethod(field, fieldKey, newVariable) + ";\n";
        }

        // SchemaDatabase variables

        String createTable = SqliteRenderer.generateTableSchema(table, databaseMapping);
        createTable = createTable.replace("\n", "\" + \n" + TAB + TAB + "\"");
        createTable = createTable.replace("\t", ""); // remove tabs
//        createTable = createTable.replace(";", ""); // remove last ;
        myClass.addConstant("String", "CREATE_TABLE", createTable);

        myClass.addConstant("String", "DROP_TABLE", SchemaRenderer.generateDropSchema(true, table));

        // Content values

        // All keys constant
        if (!myClass.isEnum()) {
            myClass.addImport("android.content.ContentValues");
            myClass.addImport("android.database.Cursor");

            String allKeysDefaultValue = "new String[] {\n";
            boolean hasKey = false;
            for (String key : keys) {
                if (hasKey) {
                    allKeysDefaultValue += ",\n";
                }
                allKeysDefaultValue += TAB + TAB + key;
                hasKey = true;
            }
            allKeysDefaultValue += "}";

//            JavaVariable allKeysVar = new JavaVariable("String[]", ALL_KEYS_VAR_NAME);
//            allKeysVar.setDefaultValue(allKeysDefaultValue);
//            allKeysVar.setAccess(Access.PROTECTED);
//            myClass.addConstant()
//            allKeysVar.setStatic(true);
//            myClass.addVariable(allKeysVar);
            JavaVariable allKeysVar = myClass.addConstant("String[]", ALL_KEYS_VAR_NAME, allKeysDefaultValue);
            allKeysVar.setAccess(Access.DEFAULT_NONE);
            myClass.addMethod(Access.PUBLIC, "String[]", "getAllKeys", "return " + ALL_KEYS_VAR_NAME + ".clone();").addAnnotation("Override");

            contentValuesContent += "return values;";
            myClass.addMethod(Access.PUBLIC, "ContentValues", "getContentValues", contentValuesContent).addAnnotation("Override");

            List<JavaVariable> setCValuesParams = new ArrayList<>();
            setCValuesParams.add(new JavaVariable("ContentValues", "values"));
            myClass.addMethod(Access.PUBLIC, "void", "setContent", setCValuesParams, setContentValuesContent);

            List<JavaVariable> setCCursorParams = new ArrayList<>();
            setCCursorParams.add(new JavaVariable("Cursor", "cursor"));
            myClass.addMethod(Access.PUBLIC, "void", "setContent", setCCursorParams, setContentCursorContent).addAnnotation("Override");
        }

        // methods
        addForgeignKeyData(database, table, packageName);

        // add method to cleanup many-to-one left-overs
        if (!myClass.isEnum()) {
            List<JavaVariable> orphanParams = new ArrayList<>();

            if (cleanupOrphansContent.length() > 0) {
                myClass.addMethod(Access.PROTECTED, "void", CLEANUP_ORPHANS_METHOD_NAME, orphanParams, cleanupOrphansContent.toString());
            }

            // to String method
            toStringContent.append("return text;\n");
            JavaMethod toStringMethod = myClass.addMethod(Access.PUBLIC, "String", "toString", toStringContent.toString());
            toStringMethod.addAnnotation("Override");

            // new record check
            myClass.addMethod(Access.PUBLIC, "boolean", "isNewRecord", "return getPrimaryKeyID() <= 0;");

            // testing methods
            JavaMethod toStringTestMethod = myTestClass.addMethod(Access.PUBLIC, "void", "testToString", "assertNotNull(testRecord.toString());");
            toStringTestMethod.addAnnotation("Test");
        }

        // Enum classes need a cleanTable and createTable
        if (myClass.isEnum()) {
            // Remove to allow the change of the database
//            myClass.addImport("android.database.sqlite.SQLiteDatabase");
//            myClass.addImport(packageName.substring(0, packageName.lastIndexOf('.')) + ".BaseManager");
//
//            List<JavaVariable> tableParams = new ArrayList<JavaVariable>();
//            tableParams.add(new JavaVariable("SQLiteDatabase", "db"));

//            myClass.addMethod(Access.PUBLIC, "void", "cleanTable", tableParams, "BaseManager.executeSQL(db, DROP_TABLE);").setStatic(true);
//            myClass.addMethod(Access.PUBLIC, "void", "createTable", tableParams, "BaseManager.executeSQL(db, CREATE_TABLE);").setStatic(true);
        }

    }


    /**
     * For method setContent(ContentValues values).
     */
    private String getContentValuesGetterMethod(SchemaTableField field, String paramValue, JavaVariable newVariable) {
        if (field.isEnumeration()) {
            return newVariable.getDataType() + ".values()[values.getAsInteger(" + paramValue + ")]";
        }

        Class<?> type = field.getJavaClassType();
        if (type == int.class || type == Integer.class) {
            return "values.getAsInteger(" + paramValue + ")";
        } else if (type == String.class) {
            return "values.getAsString(" + paramValue + ")";
        } else if (type == long.class || type == Long.class) {
            return "values.getAsLong(" + paramValue + ")";
        } else if (type == boolean.class || type == Boolean.class) {
            return "values.getAsBoolean(" + paramValue + ")";
        } else if (type == Date.class) {
            SchemaFieldType fieldType = field.getJdbcDataType();
            if (fieldType == SchemaFieldType.DATE) {
                if (dateTimeSupport) {
                    return "dbStringToDateTime(values.getAsString(" + paramValue + "))";
                } else {
                    return "dbStringToDate(values.getAsString(" + paramValue + "))";
                }
            } else {
                if (dateTimeSupport) {
                    return "new org.joda.time.DateTime(values.getAsLong(" + paramValue + "))";
                } else {
                    return "new java.util.Date(values.getAsLong(" + paramValue + "))";
                }
            }
        } else if (type == float.class || type == Float.class) { // || type == Fraction.class || type == Money.class) {
            return "values.getAsFloat(" + paramValue + ")";
        } else if (type == double.class || type == Double.class) {
            return "values.getAsDouble(" + paramValue + ")";
        } else {
            return "[[UNHANDLED FIELD TYPE: " + type + "]]";
        }
    }

    /**
     * For method setContent(Cursor cursor).
     */
    private String getContentValuesCursorGetterMethod(SchemaTableField field, String paramValue, JavaVariable newVariable) {
        if (field.isEnumeration()) {
            return newVariable.getDataType() + ".values()[cursor.getInt(cursor.getColumnIndex(" + paramValue + "))]";
        }

        Class<?> type = field.getJavaClassType();
        if (type == int.class || type == Integer.class) {
            return "cursor.getInt(cursor.getColumnIndex(" + paramValue + "))";
        } else if (type == String.class) {
            return "cursor.getString(cursor.getColumnIndex(" + paramValue + "))";
        } else if (type == long.class || type == Long.class) {
            return "cursor.getLong(cursor.getColumnIndex(" + paramValue + "))";
        } else if (type == boolean.class || type == Boolean.class) {
            return "cursor.getInt(cursor.getColumnIndex(" + paramValue + ")) != 0 ? true : false";
        } else if (type == Date.class) {
            SchemaFieldType fieldType = field.getJdbcDataType();
            if (fieldType == SchemaFieldType.DATE) {
                if (dateTimeSupport) {
                    return "dbStringToDateTime(cursor.getString(cursor.getColumnIndex(" + paramValue + ")))";
                } else {
                    return "dbStringToDate(cursor.getString(cursor.getColumnIndex(" + paramValue + ")))";
                }
            } else {
                if (dateTimeSupport) {
                    return "!cursor.isNull(cursor.getColumnIndex(" + paramValue + ")) ? new org.joda.time.DateTime(cursor.getLong(cursor.getColumnIndex(" + paramValue + "))) : null";
                } else {
                    return "!cursor.isNull(cursor.getColumnIndex(" + paramValue + ")) ? new java.util.Date(cursor.getLong(cursor.getColumnIndex(" + paramValue + "))) : null";
                }
            }
        } else if (type == float.class || type == Float.class) { // || type == Fraction.class || type == Money.class) {
            return "cursor.getFloat(cursor.getColumnIndex(" + paramValue + "))";
        } else if (type == double.class || type == Double.class) {
            return "cursor.getDouble(cursor.getColumnIndex(" + paramValue + "))";
        } else {
            return "[[UNHANDLED FIELD TYPE: " + type + "]]";
        }
    }

    private void createToStringMethodContent(final SchemaTableField field, final String fieldNameJavaStyle) {
        if (field.getJdbcDataType() != SchemaFieldType.BLOB && field.getJdbcDataType() != SchemaFieldType.CLOB) {
            // toString
            toStringContent.append("text += \"").append(fieldNameJavaStyle).append(" = \"+ ").append(fieldNameJavaStyle).append(" +\"\\n\";\n");
        }
    }

    private JavaVariable generateEnumeration(SchemaTableField field, String fieldNameJavaStyle, String packageName, SchemaDatabase database) {
        JavaVariable newVariable;
        if (field.getJdbcDataType().isNumberDataType()) {
            if (field.getForeignKeyTable().length() > 0) {
                // define name of enum
                ClassInfo enumClassInfo = database.getTableClassInfo(field.getForeignKeyTable());
                String enumName = enumClassInfo.getClassName();

                // local definition of enumeration?
                List<String> localEnumerations = field.getEnumValues();
                if (localEnumerations != null && localEnumerations.size() > 0) {
                    myClass.addEnum(enumName, field.getEnumValues());
                } else {
                    // we must import the enum
                    String enumPackage = enumClassInfo.getPackageName(packageName) + "." + enumName;

                    // build foreign key packagename
//                    String[] packageElements = packageName.split("\\.");
//                    for (int i = 0; i < packageElements.length - 1; i++) {
//                        enumPackage += packageElements[i] + ".";
//                    }
//                    enumPackage += enumName.toLowerCase() + "." + enumName;


                    myClass.addImport(enumPackage);
                }

                newVariable = new JavaVariable(enumName, fieldNameJavaStyle);
                newVariable.setGenerateSetterGetter(true);
                newVariable.setDefaultValue(enumName + "." + field.getEnumerationDefault(), false);

                addSetterGetterTest(newVariable);
            } else {
                // ENUM with out a foreign key table
                String javaStyleFieldName = field.getName(true);
                String firstChar = javaStyleFieldName.substring(0, 1).toUpperCase();
                String enumName = firstChar + javaStyleFieldName.substring(1);

                if (useInnerEnums) {
                    myClass.addEnum(enumName, field.getEnumValues());
                } else {
                    enumerationClasses.add(new JavaEnum(enumName, field.getEnumValues()));
                }

                newVariable = new JavaVariable(enumName, fieldNameJavaStyle);
                newVariable.setGenerateSetterGetter(true);
                newVariable.setDefaultValue(enumName + "." + field.getEnumerationDefault(), false);

                addSetterGetterTest(newVariable);
            }
        } else {
            newVariable = new JavaVariable(field.getJavaTypeText(), fieldNameJavaStyle);
        }

        return newVariable;
    }

    private JavaVariable generateFieldVariable(String fieldNameJavaStyle, SchemaTableField field) {
        JavaVariable newVariable;

        String typeText = field.getJavaTypeText();
        String defaultValue = field.getFormattedClassDefaultValue();

//        boolean fractionType = typeText.endsWith("Fraction");
//        boolean moneyType = typeText.endsWith("Money");
        boolean dateType = typeText.endsWith("Date");

        // Special handling for Fraction and Money
//        if (!field.isJavaTypePrimative() && (fractionType || moneyType)) {
//            // both Money and Fraction are both float at the core
//            String dataType = "float";
//            newVariable = new JavaVariable(dataType, fieldNameJavaStyle);
//
//            // custom setters and getters to change primative to Fraction or Money
//            JavaMethod setterMethod = new JavaMethod(Access.PUBLIC, "void", newVariable.getSetterMethodName());
//            setterMethod.addParameter(new JavaVariable(typeText, newVariable.getName()));
//            setterMethod.setContent("this." + newVariable.getName() + " = " + newVariable.getName() + ".floatValue();");
//            myClass.addMethod(setterMethod);
//
//            JavaMethod getterMethod = new JavaMethod(Access.PUBLIC, typeText, newVariable.getGetterMethodName());
//            getterMethod.setContent("return new " + typeText + "(" + newVariable.getName() + ");");
//            myClass.addMethod(getterMethod);
//        } else {
        if (dateType && dateTimeSupport) {
            newVariable = new JavaVariable("org.joda.time.DateTime", fieldNameJavaStyle);
        } else {
            newVariable = new JavaVariable(typeText, fieldNameJavaStyle);
        }

        SchemaFieldType fieldType = field.getJdbcDataType();
        boolean immutableDate = field.getJavaClassType() == Date.class && dateTimeSupport; // org.joda.time.DateTime IS immutable
        if (!fieldType.isJavaTypePrimative() && !fieldType.isJavaTypeImmutable() && !immutableDate) {
            newVariable.setCloneSetterGetterVar(true);
        }


        newVariable.setGenerateSetterGetter(true);
        addSetterGetterTest(newVariable);
//        }

        newVariable.setDefaultValue(defaultValue);

        return newVariable;
    }

    private void generateManyToOne(SchemaDatabase dbSchema, String packageName, SchemaTableField field) {
        String fkTableName = field.getForeignKeyTable();
        ClassInfo fkTableClassInfo = dbSchema.getTableClassInfo(fkTableName);
        String fkTableClassName = fkTableClassInfo.getClassName();
        String varName = field.getVarName();
        if (varName.equals("")) {
            varName = JavaClass.formatToJavaVariable(fkTableClassName);
        }

        String newImport = fkTableClassInfo.getPackageName(packageName) + ".*";
        myClass.addImport(newImport);
        JavaVariable manyToOneVar = new JavaVariable(fkTableClassName, varName);

        myClass.addVariable(manyToOneVar, true);
    }

    private void generateOneToMany(SchemaDatabase database, String packageName, SchemaTableField field) {
        String fkTableName = field.getForeignKeyTable();
        ClassInfo fkTableClassInfo = database.getTableClassInfo(fkTableName);
        String fkTableClassName = fkTableClassInfo.getClassName();
        String varName = field.getVarName();
        if (varName.equals("")) {
            varName = JavaClass.formatToJavaVariable(fkTableClassName);
        }

        String newImport = fkTableClassInfo.getPackageName(packageName) + ".*";
        myClass.addImport(newImport);
        JavaVariable manyToOneVar = new JavaVariable(fkTableClassName, varName);

        myClass.addVariable(manyToOneVar, true);
    }

    private void generateOneToOne(SchemaDatabase database, String packageName, SchemaTableField field) {
        String fkTableName = field.getForeignKeyTable();
        ClassInfo fkTableClassInfo = database.getTableClassInfo(fkTableName);
        String fkTableClassName = fkTableClassInfo.getClassName();
        String varName = field.getVarName();
        if (varName.equals("")) {
            varName = JavaClass.formatToJavaVariable(fkTableClassName);
        }

        String newImport = fkTableClassInfo.getPackageName(packageName) + ".*";
        myClass.addImport(newImport);
        JavaVariable oneToOneVar = new JavaVariable(fkTableClassName, varName);

        myClass.addVariable(oneToOneVar, true);
    }

    private void addForgeignKeyData(SchemaDatabase database, SchemaTable table, String packageName) {
        String TAB = JavaClass.getTab();

        // find any other tables that depend on this one (MANYTOONE) or other tables this table depends on (ONETOONE)
        for (SchemaTable tmpTable : database.getTables()) {
            List<SchemaTableField> fkFields = tmpTable.getForeignKeyFields(table.getName());

            for (SchemaTableField fkField : fkFields) {
                switch (fkField.getForeignKeyType()) {
                    case ONETOMANY:
                        String fkTableName = tmpTable.getName();
                        ClassInfo fkTableClassInfo = database.getTableClassInfo(fkTableName);
                        String fkTableClassName = fkTableClassInfo.getClassName();
                        String fkTableVarName = JavaClass.formatToJavaVariable(fkTableClassName); // mealItem
                        String newImport = fkTableClassInfo.getPackageName(packageName) + ".*";

                        String items = fkTableVarName + "Items";
                        String itemsToDelete = fkTableVarName + "ItemsToDelete";

                        myClass.addImport(newImport);

                        myClass.addImport("java.util.Set");
                        myClass.addImport("java.util.HashSet");
                        String listType = "Set<" + fkTableClassName + ">";
                        String defaultListTypeValue = "new HashSet<" + fkTableClassName + ">()";
                        JavaVariable itemsList = myClass.addVariable(listType, items);
                        itemsList.setDefaultValue(defaultListTypeValue);

                        myClass.addMethod(Access.PUBLIC, listType, JavaVariable.getGetterMethodName(listType, items), "return java.util.Collections.unmodifiableSet(" + items + ");");

                        ClassInfo mappedByClassInfo = database.getTableClassInfo(fkField.getForeignKeyTable());
                        JavaClass.formatToJavaVariable(mappedByClassInfo.getClassName());

                        // addItem method
                        JavaMethod addMethod = new JavaMethod("add" + fkTableClassName);
                        addMethod.setAccess(Access.PUBLIC);
                        addMethod.addParameter(new JavaVariable(fkTableClassName, fkTableVarName));
                        String addMethodContent = "";

                        ClassInfo myTableClassInfo = database.getTableClassInfo(fkField.getForeignKeyTable());
                        String tableClassName = myTableClassInfo.getClassName();


                        String fieldName = fkField.getVarName();
                        if (fieldName == null || fieldName.length() == 0) {
                            fieldName = tableClassName;
                        }

                        String setterMethodName = "set" + fieldName.toUpperCase().charAt(0) + fieldName.substring(1, fieldName.length());

                        addMethodContent += fkTableVarName + "." + setterMethodName + "((" + tableClassName + ")this);\n";
                        addMethodContent += items + ".add(" + fkTableVarName + ");\n";
                        addMethod.setContent(addMethodContent);
                        myClass.addMethod(addMethod);

                        // deleteItem method
                        JavaVariable itemsToDeleteList = myClass.addVariable(listType, itemsToDelete);
                        itemsToDeleteList.setDefaultValue(defaultListTypeValue);

                        JavaMethod removeMethod = new JavaMethod("delete" + fkTableClassName);
                        removeMethod.setAccess(Access.PUBLIC);
                        removeMethod.addParameter(new JavaVariable(fkTableClassName, fkTableVarName));

                        String removeMethodContent = "";
                        removeMethodContent += "if (" + fkTableVarName + " == null) {\n";
                        removeMethodContent += TAB + "return;\n";
                        removeMethodContent += "}\n\n";
                        removeMethodContent += "java.util.Iterator<" + fkTableClassName + "> itr = " + items + ".iterator();\n";
                        removeMethodContent += "while (itr.hasNext()) {\n";
                        removeMethodContent += TAB + fkTableClassName + " item = itr.next();\n";
                        removeMethodContent += TAB + "if (item.equals(" + fkTableVarName + ")) {\n";
                        removeMethodContent += TAB + TAB + "itr.remove();\n";
                        removeMethodContent += TAB + TAB + itemsToDelete + ".add(item);\n";
                        removeMethodContent += TAB + TAB + "break;\n";
                        removeMethodContent += TAB + "}\n";
                        removeMethodContent += TAB + "if (!itr.hasNext()) {\n";
                        removeMethodContent += TAB + TAB + "throw new IllegalStateException(\"deleteItem failed: Cannot find itemID \"+ " + fkTableVarName + ".getPrimaryKeyID());\n";
                        removeMethodContent += TAB + "}\n";
                        removeMethodContent += "}";

                        removeMethod.setContent(removeMethodContent);
                        myClass.addMethod(removeMethod);

                        // add to cleanup orphans
                        cleanupOrphansContent.append("for (").append(fkTableClassName).append(" itemToDelete : ").append(itemsToDelete).append(") {\n");
                        cleanupOrphansContent.append(TAB).append("try {\n");
                        cleanupOrphansContent.append(TAB).append(TAB).append("em.remove(itemToDelete);\n");
                        cleanupOrphansContent.append(TAB).append("} catch(RuntimeException e) {// do nothing... it is ok if it does not exist\n");
                        cleanupOrphansContent.append(TAB).append("}\n");
                        cleanupOrphansContent.append("}\n\n");
                        break;
                    case ONETOONE:
                        // do nothing .... one to one stuff happens below
                        break;

                    case IGNORE:
                    default:
                }
            }
        }
    }

    public static String createClassName(SchemaTable table) {
        if (table.isEnumerationTable()) {
            return table.getClassName();
        } else {
            return table.getClassName() + "BaseRecord";
        }
    }

    public void writeToFile(String directoryName) {
        myClass.writeToDisk(directoryName);

        for (JavaEnum enumClass : enumerationClasses) {
            enumClass.writeToDisk(directoryName);
        }
    }

    public void writeTestsToFile(String directoryName) {
        if (writeTestClass) {
            myTestClass.writeToDisk(directoryName);
        }
    }

    private void initTestClass() {
        myTestClass.addImport("org.junit.*");
        myTestClass.addImport("static org.junit.Assert.*");

        // header comment
        //Date now = new Date();
        //SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss");
        String fileHeaderComment;
        fileHeaderComment = "/*\n";
        fileHeaderComment += " * " + myTestClass.getName() + ".java\n";
        fileHeaderComment += " * \n";
        fileHeaderComment += " * GENERATED FILE - DO NOT EDIT\n";
        fileHeaderComment += " * \n";
        fileHeaderComment += " */\n";
        myTestClass.setFileHeaderComment(fileHeaderComment);

        if (useLegacyJUnit) {
            List<JavaVariable> params = new ArrayList<>();
            params.add(new JavaVariable("String", "testName"));
            myTestClass.addConstructor(Access.PUBLIC, params, "super(testName);");
        } else {
            myTestClass.setCreateDefaultConstructor(true);
        }

        // variables
        myTestClass.addVariable(myClass.getName(), "testRecord");

        // methods
        JavaMethod setUpMethod = myTestClass.addMethod(Access.PUBLIC, "void", "setUp", "testRecord = new " + myClass.getName() + "();\nassertNotNull(testRecord);");
        if (!useLegacyJUnit) {
            setUpMethod.addAnnotation("Before");
        }

        JavaMethod tearDownMethod = myTestClass.addMethod(Access.PUBLIC, "void", "tearDown", null);
        if (!useLegacyJUnit) {
            tearDownMethod.addAnnotation("After");
        }
    }

    private void addSetterGetterTest(JavaVariable newVariable) {
        DataType dataType = DataType.getDataType(newVariable.getDataType());

        JavaMethod testMethod = new JavaMethod(Access.PUBLIC, "void", "test" + JavaVariable.createBeanMethodName(newVariable.getName()));
        StringBuilder testContent = new StringBuilder();

        if (!useLegacyJUnit) {
            testMethod.addAnnotation("Test");
        }

        switch (dataType) {
            case STRING:
                testContent.append("String testData = \"abc\";\n");
                testContent.append("testRecord.").append(newVariable.getSetterMethodName()).append("(testData);\n");
                testContent.append("String recordData = testRecord.").append(newVariable.getGetterMethodName()).append("();\n");
                testContent.append("assertEquals(testData, recordData);");
                break;
            case CHAR:
                testContent.append("char testData = 'z';\n");
                testContent.append("testRecord.").append(newVariable.getSetterMethodName()).append("(testData);\n");
                testContent.append("char recordData = testRecord.").append(newVariable.getGetterMethodName()).append("();\n");
                testContent.append("assertEquals(testData, recordData);");
                break;
            case BOOLEAN:
                testContent.append("boolean testData = false;\n");
                testContent.append("testRecord.").append(newVariable.getSetterMethodName()).append("(testData);\n");
                testContent.append("boolean recordData = testRecord.").append(newVariable.getGetterMethodName()).append("();\n");
                testContent.append("assertEquals(testData, recordData);");
                break;
            case INT:
                testContent.append("int testData = 123;\n");
                testContent.append("testRecord.").append(newVariable.getSetterMethodName()).append("(testData);\n");
                testContent.append("int recordData = testRecord.").append(newVariable.getGetterMethodName()).append("();\n");
                testContent.append("assertEquals(testData, recordData);");
                break;
            case FLOAT:
                testContent.append("float testData = 123.56f;\n");
                testContent.append("testRecord.").append(newVariable.getSetterMethodName()).append("(testData);\n");
                testContent.append("float recordData = testRecord.").append(newVariable.getGetterMethodName()).append("();\n");
                testContent.append("assertEquals(testData, recordData, 0);");
                break;
            case DOUBLE:
                testContent.append("double testData = 123.56;\n");
                testContent.append("testRecord.").append(newVariable.getSetterMethodName()).append("(testData);\n");
                testContent.append("double recordData = testRecord.").append(newVariable.getGetterMethodName()).append("();\n");
                testContent.append("assertEquals(testData, recordData, 0);");
                break;
            case DATE:
                myTestClass.addImport("java.util.Calendar");
                myTestClass.addImport("java.util.Date");
                testContent.append("Calendar testData = Calendar.getInstance();\n");
                testContent.append("int testYear = 1980;\n");
                testContent.append("int testMonth = 2;\n");
                testContent.append("int testDay = 1;\n");
                testContent.append("testData.set(1980, 2, 1);\n");
                testContent.append("testRecord.").append(newVariable.getSetterMethodName()).append("(testData.getTime());\n");
                testContent.append("Date recordDataDate = testRecord.").append(newVariable.getGetterMethodName()).append("();\n");
                testContent.append("Calendar recordData = Calendar.getInstance();\n");
                testContent.append("recordData.setTime(recordDataDate);\n");
                testContent.append("int year = recordData.get(Calendar.YEAR);\n");
                testContent.append("int month = recordData.get(Calendar.MONTH);\n");
                testContent.append("int day = recordData.get(Calendar.DATE);\n");
                testContent.append("assertEquals(testYear, year);\n");
                testContent.append("assertEquals(testMonth, month);\n");
                testContent.append("assertEquals(testDay, day);\n");
                break;

//            case OBJECT:
//                testContent.append("Object testData = 123.56;\n");
//                testContent.append("testRecord."+ newVariable.getSetterMethodName() +"(testData);\n");
//                testContent.append("double recordData = testRecord."+ newVariable.getGetterMethodName() +"();\n");
//                testContent.append("assertEquals(testData, recordData);");

        }

        testMethod.setContent(testContent.toString());
        myTestClass.addMethod(testMethod);
    }

    public void setDateTimeSupport(boolean dateTimeSupport) {
        this.dateTimeSupport = dateTimeSupport;
    }

    public void setInjectionSupport(boolean injectionSupport) {
        this.injectionSupport = injectionSupport;
    }
}
