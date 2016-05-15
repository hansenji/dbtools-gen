package org.dbtools.util;

/**
 * User: jcampbell
 * Date: 2/13/14
 */
public class JavaUtil {
    public static String sqlNameToJavaVariableName(String name) {
        String javaFieldNameStyleName = "";


        // check to see if all letters are uppercase
        boolean isAllUppercase = false;
        for (char currentChar : name.toCharArray()) {
            if (Character.isUpperCase(currentChar) && Character.isLetter(currentChar)) {
                isAllUppercase = true;
            } else if (Character.isLetter(currentChar)) {
                isAllUppercase = false;
                break;
            }
        }

        String nameToConvert;
        // if all uppercase force lowercase on all letter
        if (isAllUppercase) {
            nameToConvert = name.toLowerCase();
        } else {
            nameToConvert = name;
        }

        for (int i = 0; i < nameToConvert.length(); i++) {
            char currentChar = nameToConvert.charAt(i);

            // REMOVE _ and replace next letter with an uppercase letter
            switch (currentChar) {
                case '_':
                    // move to the next letter
                    i++;
                    currentChar = nameToConvert.charAt(i);

                    if (!javaFieldNameStyleName.isEmpty()) {
                        javaFieldNameStyleName += Character.toString(currentChar).toUpperCase();
                    } else {
                        javaFieldNameStyleName += Character.toString(currentChar);
                    }
                    break;
                default:
                    javaFieldNameStyleName += currentChar;
            }
        }

        return javaFieldNameStyleName;
    }

    public static String nameToJavaConst(String nameToConvert) {
        StringBuilder constName = new StringBuilder();

        for (int i = 0; i < nameToConvert.length(); i++) {
            char currentChar = nameToConvert.charAt(i);

            // add _ if current character is upper and last is lower
            if (Character.isUpperCase(currentChar) && i > 0) {
                char lastChar = nameToConvert.charAt(i - 1);
                if (Character.isLowerCase(lastChar)) {
                    constName.append('_');
                }
            }

            constName.append(Character.toUpperCase(currentChar));
        }

        return constName.toString();
    }

    public static String createTablePackageName(String packageBase, String tableClassName) {
        return packageBase + "." + tableClassName.toLowerCase();
    }

    public static String createTableImport(String packageBase, String tableClassName) {
        return createTablePackageName(packageBase, tableClassName) + "." + tableClassName;
    }
}
