package graphql.validation.constraints;

import graphql.Assert;
import graphql.GraphQLError;
import graphql.PublicSpi;
import graphql.Scalars;
import graphql.execution.ExecutionPath;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLTypeUtil;
import graphql.util.FpKit;
import graphql.validation.rules.ValidationEnvironment;
import graphql.validation.util.DirectivesAndTypeWalker;
import graphql.validation.util.Util;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.schema.GraphQLTypeUtil.isList;
import static java.util.Collections.singletonList;

@SuppressWarnings("UnnecessaryLocalVariable")
@PublicSpi
public abstract class AbstractDirectiveConstraint implements DirectiveConstraint {

    private final String name;

    public AbstractDirectiveConstraint(String name) {
        this.name = name;
    }


    @Override
    public String toString() {
        return "@" + name;
    }

    @Override
    public String getName() {
        return name;
    }


    protected String getMessageTemplate() {
        return "graphql.validation." + getName() + ".message";
    }

    @Override
    public boolean appliesTo(GraphQLFieldDefinition fieldDefinition, GraphQLFieldsContainer fieldsContainer) {
        return false;
    }

    @Override
    public boolean appliesTo(GraphQLArgument argument, GraphQLFieldDefinition fieldDefinition, GraphQLFieldsContainer fieldsContainer) {

        boolean suitable = DirectivesAndTypeWalker.isSuitable(argument, (inputType, directive) -> {
            boolean hasNamedDirective = directive.getName().equals(this.getName());
            if (hasNamedDirective) {
                inputType = Util.unwrapNonNull(inputType);
                boolean appliesToType = appliesToType(inputType);
                if (appliesToType) {
                    return true;
                }
                // if they have a @Directive on there BUT it cant handle that type
                // then is a really bad situation
                String argType = GraphQLTypeUtil.simplePrint(inputType);
                Assert.assertShouldNeverHappen("The directive rule '%s' cannot be placed on elements of type '%s'", this.getName(), argType);
            }
            return false;
        });
        return suitable;
    }

    abstract protected boolean appliesToType(GraphQLInputType inputType);

    abstract protected List<GraphQLError> runConstraint(ValidationEnvironment validationEnvironment);


    @SuppressWarnings("unchecked")
    @Override
    public List<GraphQLError> runValidation(ValidationEnvironment validationEnvironment) {

        GraphQLArgument argument = validationEnvironment.getArgument();
        Object validatedValue = validationEnvironment.getValidatedValue();
        List<GraphQLDirective> directives = argument.getDirectives();

        //
        // all the directives validation code does NOT care for NULL ness since the graphql engine covers that.
        // eg a @NonNull validation directive makes no sense in graphql like it might in Java
        //
        GraphQLInputType inputType = Util.unwrapNonNull(validationEnvironment.getFieldOrArgumentType());
        validationEnvironment = validationEnvironment.transform(b -> b.fieldOrArgumentType(inputType));

        return runValidationImpl(validationEnvironment, inputType, validatedValue, directives);
    }

    @SuppressWarnings("unchecked")
    private List<GraphQLError> runValidationImpl(ValidationEnvironment validationEnvironment, GraphQLInputType inputType, Object validatedValue, List<GraphQLDirective> directives) {
        List<GraphQLError> errors = new ArrayList<>();
        // run them in a stable order
        directives = Util.sort(directives, GraphQLDirective::getName);
        for (GraphQLDirective directive : directives) {
            // we get called for arguments and input field types which can have multiple directive constraints on them and hence no just for this one
            boolean isOurDirective = directive.getName().equals(this.getName());
            if (!isOurDirective) {
                continue;
            }

            validationEnvironment = validationEnvironment.transform(b -> b.context(GraphQLDirective.class, directive));
            //
            // now run the directive rule with this directive instance
            List<GraphQLError> ruleErrors = this.runConstraint(validationEnvironment);
            errors.addAll(ruleErrors);
        }

        if (validatedValue == null) {
            return errors;
        }

        inputType = (GraphQLInputType) GraphQLTypeUtil.unwrapNonNull(inputType);

        if (GraphQLTypeUtil.isList(inputType)) {
            List<Object> values = new ArrayList<>(FpKit.toCollection(validatedValue));
            List<GraphQLError> ruleErrors = walkListArg(validationEnvironment, (GraphQLList) inputType, values);
            errors.addAll(ruleErrors);
        }

        if (inputType instanceof GraphQLInputObjectType) {
            if (validatedValue instanceof Map) {
                Map<String, Object> objectValue = (Map<String, Object>) validatedValue;
                List<GraphQLError> ruleErrors = walkObjectArg(validationEnvironment, (GraphQLInputObjectType) inputType, objectValue);
                errors.addAll(ruleErrors);
            } else {
                Assert.assertShouldNeverHappen("How can there be a `input` object type '%s' that does not have a matching Map java value", GraphQLTypeUtil.simplePrint(inputType));
            }
        }
        return errors;
    }

    private List<GraphQLError> walkObjectArg(ValidationEnvironment validationEnvironment, GraphQLInputObjectType argumentType, Map<String, Object> objectMap) {
        List<GraphQLError> errors = new ArrayList<>();

        // run them in a stable order
        List<GraphQLInputObjectField> fieldDefinitions = Util.sort(argumentType.getFieldDefinitions(), GraphQLInputObjectField::getName);
        for (GraphQLInputObjectField inputField : fieldDefinitions) {

            GraphQLInputType fieldType = inputField.getType();
            List<GraphQLDirective> directives = inputField.getDirectives();
            Object validatedValue = objectMap.getOrDefault(inputField.getName(), inputField.getDefaultValue());
            if (validatedValue == null) {
                continue;
            }

            ExecutionPath fieldOrArgPath = validationEnvironment.getFieldOrArgumentPath().segment(inputField.getName());

            ValidationEnvironment newValidationEnvironment = validationEnvironment.transform(builder -> builder
                    .fieldOrArgumentPath(fieldOrArgPath)
                    .validatedValue(validatedValue)
                    .fieldOrArgumentType(fieldType)
            );

            List<GraphQLError> ruleErrors = runValidationImpl(newValidationEnvironment, fieldType, validatedValue, directives);
            errors.addAll(ruleErrors);
        }
        return errors;
    }

    private List<GraphQLError> walkListArg(ValidationEnvironment validationEnvironment, GraphQLList argumentType, List<Object> objectList) {
        List<GraphQLError> errors = new ArrayList<>();

        GraphQLInputType listItemType = Util.unwrapOneAndAllNonNull(argumentType);
        List<GraphQLDirective> directives;
        if (!(listItemType instanceof GraphQLDirectiveContainer)) {
            directives = Collections.emptyList();
        } else {
            directives = ((GraphQLDirectiveContainer) listItemType).getDirectives();
        }
        int ix = 0;
        for (Object value : objectList) {

            ExecutionPath fieldOrArgPath = validationEnvironment.getFieldOrArgumentPath().segment(ix);

            ValidationEnvironment newValidationEnvironment = validationEnvironment.transform(builder -> builder
                    .fieldOrArgumentPath(fieldOrArgPath)
                    .validatedValue(value)
                    .fieldOrArgumentType(listItemType)
            );

            List<GraphQLError> ruleErrors = runValidationImpl(newValidationEnvironment, listItemType, value, directives);
            errors.addAll(ruleErrors);
            ix++;
        }
        return errors;
    }


    /**
     * Returns true of the input type is one of the specified scalar types, regardless of non null ness
     *
     * @param inputType   the type to check
     * @param scalarTypes the array of scalar types
     *
     * @return true ifits oneof them
     */
    protected boolean isOneOfTheseTypes(GraphQLInputType inputType, GraphQLScalarType... scalarTypes) {
        GraphQLInputType unwrappedType = Util.unwrapNonNull(inputType);
        for (GraphQLScalarType scalarType : scalarTypes) {
            if (unwrappedType.getName().equals(scalarType.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an integer argument from a directive (or its default) and throws an assertion of the argument is null
     *
     * @param directive the directive to check
     * @param argName   the argument name
     *
     * @return a non null value
     */
    protected int getIntArg(GraphQLDirective directive, String argName) {
        GraphQLArgument argument = directive.getArgument(argName);
        if (argument == null) {
            return assertExpectedArgType(argName, "Int");
        }
        Number value = (Number) argument.getValue();
        if (value == null) {
            value = (Number) argument.getDefaultValue();
            if (value == null) {
                return assertExpectedArgType(argName, "Int");
            }
        }
        return value.intValue();
    }

    /**
     * Returns an String argument from a directive (or its default) and throws an assertion of the argument is null
     *
     * @param directive the directive to check
     * @param argName   the argument name
     *
     * @return a non null value
     */
    protected String getStrArg(GraphQLDirective directive, String argName) {
        GraphQLArgument argument = directive.getArgument(argName);
        if (argument == null) {
            return assertExpectedArgType(argName, "String");
        }
        String value = (String) argument.getValue();
        if (value == null) {
            value = (String) argument.getDefaultValue();
            if (value == null) {
                return assertExpectedArgType(argName, "String");
            }
        }
        return value;
    }

    /**
     * Returns an boolean argument from a directive (or its default) and throws an assertion of the argument is null
     *
     * @param directive the directive to check
     * @param argName   the argument name
     *
     * @return a non null value
     */
    protected boolean getBoolArg(GraphQLDirective directive, String argName) {
        GraphQLArgument argument = directive.getArgument(argName);
        if (argument == null) {
            return assertExpectedArgType(argName, "Boolean");
        }
        Object value = argument.getValue();
        if (value == null) {
            value = argument.getDefaultValue();
            if (value == null) {
                return assertExpectedArgType(argName, "Boolean");
            }
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Returns the "message : String" argument from a directive or makes up one
     * called "graphql.validation.{name}.message"
     *
     * @param directive the directive to check
     *
     * @return a non null value
     */
    protected String getMessageTemplate(GraphQLDirective directive) {
        String msg = null;
        GraphQLArgument arg = directive.getArgument("message");
        if (arg != null) {
            msg = (String) arg.getValue();
            if (msg == null) {
                msg = (String) arg.getDefaultValue();
            }
        }
        if (msg == null) {
            msg = "graphql.validation." + getName() + ".message";
        }
        return msg;
    }

    /**
     * Creates a map of named parameters for message interpolation
     *
     * @param validatedValue        the value being validated
     * @param validationEnvironment the validation environment
     * @param args                  must be an key / value array with String keys as the even params and values as then odd params
     *
     * @return a map of message parameters
     */
    protected Map<String, Object> mkMessageParams(Object validatedValue, ValidationEnvironment validationEnvironment, Object... args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("validatedValue", validatedValue);
        params.put("constraint", getName());
        params.put("path", mkFieldOrArgPath(validationEnvironment));

        params.putAll(mkMap(args));
        return params;
    }


    /**
     * Makes a map of the args
     *
     * @param args must be an key / value array with String keys as the even params and values as then odd params
     *
     * @return a map of the args
     */
    protected Map<String, Object> mkMap(Object... args) {
        Map<String, Object> params = new LinkedHashMap<>();
        Assert.assertTrue(args.length % 2 == 0, "You MUST pass in an even number of arguments");
        for (int ix = 0; ix < args.length; ix = ix + 2) {
            Object key = args[ix];
            Assert.assertTrue(key instanceof String, "You MUST pass in a message param string key");
            Object val = args[ix + 1];
            params.put(String.valueOf(key), val);
        }
        return params;
    }


    private Object mkFieldOrArgPath(ValidationEnvironment validationEnvironment) {
        ExecutionPath executionPath = validationEnvironment.getExecutionPath();
        ExecutionPath fieldOrArgumentPath = validationEnvironment.getFieldOrArgumentPath();

        executionPath = Util.concatPaths(executionPath, fieldOrArgumentPath);
        return executionPath == null ? "/" : executionPath.toString();
    }

    /**
     * Creates  a new {@link graphql.GraphQLError}
     *
     * @param validationEnvironment the current validation environment
     * @param directive             the directive being run
     * @param msgParams             the map of parameters
     *
     * @return a list of a single error
     */
    protected List<GraphQLError> mkError(ValidationEnvironment validationEnvironment, GraphQLDirective directive, Map<String, Object> msgParams) {
        String messageTemplate = getMessageTemplate(directive);
        GraphQLError error = validationEnvironment.getInterpolator().interpolate(messageTemplate, msgParams, validationEnvironment);
        return singletonList(error);
    }

    /**
     * Return true if the type is a String or List type or {@link graphql.schema.GraphQLInputObjectType}, regardless of non null ness
     *
     * @param inputType the type to check
     *
     * @return true if one of the above
     */
    protected boolean isStringOrListOrMap(GraphQLInputType inputType) {
        GraphQLInputType unwrappedType = Util.unwrapOneAndAllNonNull(inputType);
        return Scalars.GraphQLString.equals(unwrappedType) ||
                isList(inputType) ||
                (unwrappedType instanceof GraphQLInputObjectType);
    }

    /**
     * Casts the object as a Map with an assertion of it is not one
     *
     * @param value the object to turn into a map
     *
     * @return a Map
     */
    @SuppressWarnings("ConstantConditions")
    protected Map asMap(Object value) {
        Assert.assertTrue(value instanceof Map, "The argument value MUST be a Map value");
        return (Map) value;
    }

    /**
     * Makes the object a BigDecimal with an assertion if we have no conversion of it
     *
     * @param value the object to turn into a BigDecimal
     *
     * @return a BigDecimal
     */
    protected BigDecimal asBigDecimal(Object value) throws NumberFormatException {
        if (value == null) {
            return Assert.assertShouldNeverHappen("Validation cant handle null objects BigDecimals");
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        String bdStr = "";
        if (value instanceof Number) {
            bdStr = value.toString();
        } else if (value instanceof String) {
            bdStr = value.toString();
        } else {
            Assert.assertShouldNeverHappen("Validation cant handle objects of type '%s' as BigDecimals", value.getClass().getSimpleName());
        }
        return new BigDecimal(bdStr);
    }

    /**
     * Makes the object a boolean with an assertion if we have no conversion of it
     *
     * @param value the boolean object
     *
     * @return a boolean
     */
    protected boolean asBoolean(Object value) {
        if (value == null) {
            return Assert.assertShouldNeverHappen("Validation cant handle null objects Booleans");
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else {
            return Assert.assertShouldNeverHappen("Validation cant handle objects of type '%s' as Booleans", value.getClass().getSimpleName());
        }
    }

    /**
     * Returns the length of a String of the size of a list or size of a Map
     *
     * @param inputType the input type
     * @param value     the value
     *
     * @return the length of a String or Map or List
     */
    protected int getStringOrObjectOrMapLength(GraphQLInputType inputType, Object value) {
        int valLen;
        if (value == null) {
            valLen = 0;
        } else if (Scalars.GraphQLString.equals(Util.unwrapNonNull(inputType))) {
            valLen = String.valueOf(value).length();
        } else if (isList(inputType)) {
            valLen = getListLength(value);
        } else {
            valLen = getObjectLen(value);
        }
        return valLen;
    }

    private int getObjectLen(Object value) {
        if (value == null) {
            return 0;
        }
        Map map = asMap(value);
        return map.size();
    }

    private int getListLength(Object value) {
        if (value instanceof Collection) {
            return ((Collection) value).size();
        } else if (value instanceof Iterable) {
            int len = 0;
            for (Object ignored : ((Iterable) value)) {
                len++;
            }
            return len;
        } else if (value != null && value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return 0;
    }

    private <T> T assertExpectedArgType(String argName, String typeName) {
        return Assert.assertShouldNeverHappen("A validation directive MUST have a '%s' argument of type '%s' with a default value", argName, typeName);
    }

}