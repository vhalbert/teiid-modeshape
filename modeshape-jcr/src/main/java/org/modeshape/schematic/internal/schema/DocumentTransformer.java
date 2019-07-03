/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.schematic.internal.schema;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import org.modeshape.schematic.SchemaLibrary;
import org.modeshape.schematic.document.Document;
import org.modeshape.schematic.document.Path;

/**
 * 
 */
public class DocumentTransformer {

    private static final String CURLY_PREFIX = "${";
    private static final String CURLY_SUFFIX = "}";
    private static final String VAR_DELIM = ",";
    private static final String DEFAULT_DELIM = ":";

    protected static interface PropertyAccessor {
        String getProperty( String name );
    }

    protected static final class PropertiesAccessor implements PropertyAccessor {
        private final Properties properties;

        protected PropertiesAccessor( Properties properties ) {
            this.properties = properties;
        }

        @Override
        public String getProperty( String name ) {
            return properties.getProperty(name);
        }
    }

    protected static final class SystemPropertyAccessor implements PropertyAccessor {
        public static final SystemPropertyAccessor INSTANCE = new SystemPropertyAccessor();

        private SystemPropertyAccessor() {
            // prevent instantiation
        }

        @Override
        public String getProperty( final String name ) {
            return AccessController.doPrivileged((PrivilegedAction<String>) () -> System.getProperty(name));
        }
    }

    /**
     * getSubstitutedProperty is called to perform the property substitution on the value.
     * 
     * @param value
     * @param propertyAccessor
     * @return String
     */
    public static String getSubstitutedProperty( String value,
                                                 PropertyAccessor propertyAccessor ) {

        if (value == null || value.trim().length() == 0) return value;

        StringBuilder sb = new StringBuilder(value);

        // Get the index of the first constant, if any
        int startName = sb.indexOf(CURLY_PREFIX);

        if (startName == -1) return value;

        // process as many different variable groupings that are defined, where one group will resolve to one property
        // substitution
        while (startName != -1) {
            String defaultValue = null;

            int endName = sb.indexOf(CURLY_SUFFIX, startName);

            if (endName == -1) {
                // if no suffix can be found, then this variable was probably defined incorrectly
                // but return what there is at this point
                return sb.toString();
            }

            String varString = sb.substring(startName + 2, endName);
            if (varString.indexOf(DEFAULT_DELIM) > -1) {
                List<String> defaults = split(varString, DEFAULT_DELIM);

                // get the property(s) variables that are defined left of the default delimiter.
                varString = defaults.get(0);

                // if the default is defined, then capture in case none of the other properties are found
                if (defaults.size() == 2) {
                    defaultValue = defaults.get(1);
                }
            }

            String constValue = null;
            // split the property(s) based VAR_DELIM, when multiple property options are defined
            List<String> vars = split(varString, VAR_DELIM);
            for (final String var : vars) {
                constValue = System.getenv(var);
                if (constValue == null) {
                    constValue = propertyAccessor.getProperty(var);
                }

                // the first found property is the value to be substituted
                if (constValue != null) {
                    break;
                }
            }

            // if no property is found to substitute, then use the default value, if defined
            if (constValue == null && defaultValue != null) {
                constValue = defaultValue;
            }

            if (constValue != null) {
                sb = sb.replace(startName, endName + 1, constValue);
                // Checking for another constants
                startName = sb.indexOf(CURLY_PREFIX);

            } else {
                // continue to try to substitute for other properties so that all defined variables
                // are tried to be substituted for
                startName = sb.indexOf(CURLY_PREFIX, endName);

            }

        }

        return sb.toString();
    }

    /**
     * Split a string into pieces based on delimiters. Similar to the perl function of the same name. The delimiters are not
     * included in the returned strings.
     * 
     * @param str Full string
     * @param splitter Characters to split on
     * @return List of String pieces from full string
     */
    private static List<String> split( String str,
                                       String splitter ) {
        StringTokenizer tokens = new StringTokenizer(str, splitter);
        ArrayList<String> l = new ArrayList<>(tokens.countTokens());
        while (tokens.hasMoreTokens()) {
            l.add(tokens.nextToken());
        }
        return l;
    }

    /**
     * An implementation of {@link Document.ValueTransformer} that replaces variables in the
     * field values with values from the system properties. Only string values are considered, since other types cannot contain
     * variables (and since the transformers are never called on Document or List values).
     * <p>
     * Variables may appear anywhere within a string value, and multiple variables can be used within the same value. Variables
     * take the form:
     * 
     * <pre>
     *    variable := '${' variableNames [ ':' defaultValue ] '}'
     *    
     *    variableNames := variableName [ ',' variableNames ]
     *    
     *    variableName := /* any characters except ',' and ':' and '}'
     *    
     *    defaultValue := /* any characters except
     * </pre>
     * 
     * Note that <i>variableName</i> is the name used to look up a the property.
     * </p>
     * Notice that the syntax supports multiple <i>variables</i>. The logic will process the <i>variables</i> from let to right,
     * until an existing property is found. And at that point, it will stop and will not attempt to find values for the other
     * <i>variables</i>.
     * <p>
     */
    public static final class PropertiesTransformer implements Document.ValueTransformer {

        private final PropertiesAccessor accessor;

        public PropertiesTransformer( Properties properties ) {
            this.accessor = new PropertiesAccessor(properties);
        }

        @Override
        public Object transform( String name,
                                 Object value ) {
            // Only look at string values ...
            if (value instanceof String) {
                return getSubstitutedProperty((String)value, this.accessor);
            }
            return value;
        }
    }

    /**
     * An implementation of {@link Document.ValueTransformer} that replaces variables in the
     * field values with values from the system properties. Only string values are considered, since other types cannot contain
     * variables (and since the transformers are never called on Document or List values).
     * <p>
     * Variables may appear anywhere within a string value, and multiple variables can be used within the same value. Variables
     * take the form:
     * 
     * <pre>
     *    variable := '${' variableNames [ ':' defaultValue ] '}'
     *    
     *    variableNames := variableName [ ',' variableNames ]
     *    
     *    variableName := /* any characters except ',' and ':' and '}'
     *    
     *    defaultValue := /* any characters except
     * </pre>
     * 
     * Note that <i>variableName</i> is the name used to look up a System property via {@link System#getProperty(String)}.
     * </p>
     * Notice that the syntax supports multiple <i>variables</i>. The logic will process the <i>variables</i> from let to right,
     * until an existing System property is found. And at that point, it will stop and will not attempt to find values for the
     * other <i>variables</i>.
     * <p>
     */
    public static final class SystemPropertiesTransformer implements Document.ValueTransformer {

        @Override
        public Object transform( String name,
                                 Object value ) {
            // Only look at string values ...
            if (value instanceof String) {
                return getSubstitutedProperty((String)value, SystemPropertyAccessor.INSTANCE);
            }
            return value;
        }
    }

    /**
     * Return a copy of the supplied document that contains converted values for all of the fields (including in the nested
     * documents and arrays) that have values that are of the wrong type but can be converted to be of the correct type.
     * <p>
     * This method does nothing and returns the original document if there are no changes to be made.
     * </p>
     * 
     * @param original the original document that contains fields with mismatched values; may not be null
     * @param results the results of the {@link SchemaLibrary#validate(Document, String) JSON Schema
     *        validation} and which contains the {@link SchemaLibrary.MismatchedTypeProblem type mismatch errors}
     * @return the document with all of the conversions made the its fields and the fields of nested documents, or the original
     *         document if there are no conversions to be made; never null
     */
    public static Document convertValuesWithMismatchedTypes( Document original,
                                                             SchemaLibrary.Results results ) {
        if (results == null || !results.hasProblems()) return original;

        // Create a conversion object for each of the field values with mismatched (but convertable) types ...
        LinkedList<Conversion> conversions = new LinkedList<>();
        for (SchemaLibrary.Problem problem : results) {
            if (problem instanceof SchemaLibrary.MismatchedTypeProblem) {
                conversions.add(new Conversion((SchemaLibrary.MismatchedTypeProblem)problem));
            }
        }
        if (conversions.isEmpty()) return original;

        // Transform the original document, starting at the first level ...
        return convertValuesWithMismatchedTypes(original, 0, conversions);
    }

    protected static Document convertValuesWithMismatchedTypes( Document original,
                                                                int level,
                                                                LinkedList<Conversion> conversions ) {
        // Create a placeholder for the new field values for this document ...
        Map<String, Object> changedFields = new HashMap<>();

        // Now apply the changes to this document and prepare to coallesce the changes for the nested documents ...
        int nextLevel = level + 1;
        Map<String, LinkedList<Conversion>> nextLevelConversionsBySegment = new HashMap<>();
        for (Conversion conversion : conversions) {
            Path path = conversion.getPath();
            assert path.size() > level;
            String segment = path.get(level);
            if (path.size() == nextLevel) {
                // This is the last segment for this path, so change the output document's field ...
                changedFields.put(segment, conversion.getConvertedValue());
            } else {
                // Otherwise, the path is for the nested document ...
                LinkedList<Conversion> nestedConversions = nextLevelConversionsBySegment.get(segment);
                if (nestedConversions == null) {
                    nestedConversions = new LinkedList<>();
                    nextLevelConversionsBySegment.put(segment, nestedConversions);
                }
                nestedConversions.add(conversion);
            }
        }

        // Now apply all of the conversions for the nested documents,
        // getting the results and storing them in the 'changedFields' ...
        for (Map.Entry<String, LinkedList<Conversion>> entry : nextLevelConversionsBySegment.entrySet()) {
            String segment = entry.getKey();
            LinkedList<Conversion> nestedConversions = entry.getValue();
            Document nested = original.getDocument(segment);
            Document newDoc = convertValuesWithMismatchedTypes(nested, nextLevel, nestedConversions);
            changedFields.put(segment, newDoc);
        }

        // Now create a copy of the original document but with the changed fields ...
        return original.with(changedFields);
    }

    protected static final class Conversion implements Comparable<Conversion> {
        private final SchemaLibrary.MismatchedTypeProblem problem;

        protected Conversion( SchemaLibrary.MismatchedTypeProblem problem ) {
            this.problem = problem;
        }

        @Override
        public int compareTo( Conversion that ) {
            if (this == that) return 0;
            return this.problem.getPath().compareTo(that.problem.getPath());
        }

        public Path getPath() {
            return this.problem.getPath();
        }

        public Object getConvertedValue() {
            return this.problem.getConvertedValue();
        }
    }

}
