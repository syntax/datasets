package com.fasterxml.jackson.databind;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.ObjectIdGenerator;
import com.fasterxml.jackson.annotation.ObjectIdResolver;

import com.fasterxml.jackson.core.*;

import com.fasterxml.jackson.databind.cfg.ContextAttributes;
import com.fasterxml.jackson.databind.deser.*;
import com.fasterxml.jackson.databind.deser.impl.ObjectIdReader;
import com.fasterxml.jackson.databind.deser.impl.ReadableObjectId;
import com.fasterxml.jackson.databind.deser.impl.TypeWrappedDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.*;

/**
 * Context for the process of deserialization a single root-level value.
 * Used to allow passing in configuration strategySettings and reusable temporary
 * objects (scrap arrays, containers).
 *<p>
 * Instance life-cycle is such that a partially configured "blueprint" object
 * is registered with {@link ObjectMapper} (and {@link ObjectReader},
 * and when actual instance is needed for deserialization,
 * a fully configured instance will be created using a method in extended internal
 *  API of sub-class
 * ({@link com.fasterxml.jackson.databind.deser.DefaultDeserializationContext#createInstance}).
 * Each instance is guaranteed to only be used from single-threaded context;
 * instances may be reused if (and only if) no configuration has changed.
 *<p>
 * Defined as abstract class so that implementations must define methods
 * for reconfiguring blueprints and creating instances.
 */
public abstract class DeserializationContext
    extends DatabindContext
    implements java.io.Serializable
{
    private static final long serialVersionUID = 1L; // 2.6

    /*
    /**********************************************************
    /* Configuration, immutable
    /**********************************************************
     */
    
    /**
     * Object that handle details of {@link JsonDeserializer} caching.
     */
    protected final DeserializerCache _cache;

    /*
    /**********************************************************
    /* Configuration, changeable via fluent factories
    /**********************************************************
     */

    /**
     * Read-only factory instance; exposed to let
     * owners (<code>ObjectMapper</code>, <code>ObjectReader</code>)
     * access it.
     */
    protected final DeserializerFactory _factory;

    /*
    /**********************************************************
    /* Configuration that gets set for instances (not blueprints)
    /* (partly denormalized for performance)
    /**********************************************************
     */

    /**
     * Generic deserialization processing configuration
     */
    protected final DeserializationConfig _config;

    /**
     * Bitmap of {@link DeserializationFeature}s that are enabled
     */
    protected final int _featureFlags;

    /**
     * Currently active view, if any.
     */
    protected final Class<?> _view;

    /**
     * Currently active parser used for deserialization.
     * May be different from the outermost parser
     * when content is buffered.
     */
    protected transient JsonParser _parser;
    
    /**
     * Object used for resolving references to injectable
     * values.
     */
    protected final InjectableValues _injectableValues;
    
    /*
    /**********************************************************
    /* Per-operation reusable helper objects (not for blueprints)
    /**********************************************************
     */

    protected transient ArrayBuilders _arrayBuilders;

    protected transient ObjectBuffer _objectBuffer;

    protected transient DateFormat _dateFormat;

    /**
     * Lazily-constructed holder for per-call attributes.
     * 
     * @since 2.3
     */
    protected transient ContextAttributes _attributes;

    /**
     * Type of {@link JsonDeserializer} (or, more specifically,
     *   {@link ContextualDeserializer}) that is being
     *   contextualized currently.
     *
     * @since 2.5
     */
    protected LinkedNode<JavaType> _currentType;
    
    /*
    /**********************************************************
    /* Life-cycle
    /**********************************************************
     */

    protected DeserializationContext(DeserializerFactory df) {
        this(df, null);
    }
    
    protected DeserializationContext(DeserializerFactory df,
            DeserializerCache cache)
    {
        if (df == null) {
            throw new IllegalArgumentException("Cannot pass null DeserializerFactory");
        }
        _factory = df;
        if (cache == null) {
            cache = new DeserializerCache();
        }
        _cache = cache;
        _featureFlags = 0;
        _config = null;
        _injectableValues = null;
        _view = null;
        _attributes = null;
    }

    protected DeserializationContext(DeserializationContext src,
            DeserializerFactory factory)
    {
        _cache = src._cache;
        _factory = factory;
        
        _config = src._config;
        _featureFlags = src._featureFlags;
        _view = src._view;
        _parser = src._parser;
        _injectableValues = src._injectableValues;
        _attributes = src._attributes;
    }

    /**
     * Constructor used for creating actual per-call instances.
     */
    protected DeserializationContext(DeserializationContext src,
            DeserializationConfig config, JsonParser p,
            InjectableValues injectableValues)
    {
        _cache = src._cache;
        _factory = src._factory;
        
        _config = config;
        _featureFlags = config.getDeserializationFeatures();
        _view = config.getActiveView();
        _parser = p;
        _injectableValues = injectableValues;
        _attributes = config.getAttributes();
    }

    /**
     * Copy-constructor for use with <code>copy()</code> by {@link ObjectMapper#copy()}
     */
    protected DeserializationContext(DeserializationContext src) {
        _cache = new DeserializerCache();
        _factory = src._factory;

        _config = src._config;
        _featureFlags = src._featureFlags;
        _view = src._view;
        _injectableValues = null;
    }
    
    /*
    /**********************************************************
    /* DatabindContext implementation
    /**********************************************************
     */

    @Override
    public DeserializationConfig getConfig() { return _config; }

    @Override
    public final Class<?> getActiveView() { return _view; }

    @Override
    public final boolean canOverrideAccessModifiers() {
        return _config.canOverrideAccessModifiers();
    }

    @Override
    public final boolean isEnabled(MapperFeature feature) {
        return _config.isEnabled(feature);
    }

    @Override
    public final JsonFormat.Value getDefaultPropertyFormat(Class<?> baseType) {
        return _config.getDefaultPropertyFormat(baseType);
    }

    @Override
    public final AnnotationIntrospector getAnnotationIntrospector() {
        return _config.getAnnotationIntrospector();
    }

    @Override
    public final TypeFactory getTypeFactory() {
        return _config.getTypeFactory();
    }

    /**
     * Method for accessing default Locale to use: convenience method for
     *<pre>
     *   getConfig().getLocale();
     *</pre>
     */
    @Override
    public Locale getLocale() {
        return _config.getLocale();
    }

    /**
     * Method for accessing default TimeZone to use: convenience method for
     *<pre>
     *   getConfig().getTimeZone();
     *</pre>
     */
    @Override
    public TimeZone getTimeZone() {
        return _config.getTimeZone();
    }

    /*
    /**********************************************************
    /* Access to per-call state, like generic attributes (2.3+)
    /**********************************************************
     */

    @Override
    public Object getAttribute(Object key) {
        return _attributes.getAttribute(key);
    }

    @Override
    public DeserializationContext setAttribute(Object key, Object value)
    {
        _attributes = _attributes.withPerCallAttribute(key, value);
        return this;
    }

    /**
     * Accessor to {@link JavaType} of currently contextualized
     * {@link ContextualDeserializer}, if any.
     * This is sometimes useful for generic {@link JsonDeserializer}s that
     * do not get passed (or do not retain) type information when being
     * constructed: happens for example for deserializers constructed
     * from annotations.
     * 
     * @since 2.5
     *
     * @return Type of {@link ContextualDeserializer} being contextualized,
     *   if process is on-going; null if not.
     */
    public JavaType getContextualType() {
        return (_currentType == null) ? null : _currentType.value();
    }

    /*
    /**********************************************************
    /* Public API, config setting accessors
    /**********************************************************
     */

    /**
     * Method for getting current {@link DeserializerFactory}.
     */
    public DeserializerFactory getFactory() {
        return _factory;
    }
    
    /**
     * Convenience method for checking whether specified on/off
     * feature is enabled
     */
    public final boolean isEnabled(DeserializationFeature feat) {
        /* 03-Dec-2010, tatu: minor shortcut; since this is called quite often,
         *   let's use a local copy of feature strategySettings:
         */
        return (_featureFlags & feat.getMask()) != 0;
    }

    /**
     * Bulk access method for getting the bit mask of all {@link DeserializationFeature}s
     * that are enabled.
     *
     * @since 2.6
     */
    public final int getDeserializationFeatures() {
        return _featureFlags;
    }
    
    /**
     * Bulk access method for checking that all features specified by
     * mask are enabled.
     * 
     * @since 2.3
     */
    public final boolean hasDeserializationFeatures(int featureMask) {
        return (_featureFlags & featureMask) == featureMask;
    }

    /**
     * Bulk access method for checking that at least one of features specified by
     * mask is enabled.
     * 
     * @since 2.6
     */
    public final boolean hasSomeOfFeatures(int featureMask) {
        return (_featureFlags & featureMask) != 0;
    }
    
    /**
     * Method for accessing the currently active parser.
     * May be different from the outermost parser
     * when content is buffered.
     *<p>
     * Use of this method is discouraged: if code has direct access
     * to the active parser, that should be used instead.
     */
    public final JsonParser getParser() { return _parser; }

    public final Object findInjectableValue(Object valueId,
            BeanProperty forProperty, Object beanInstance)
        throws JsonMappingException
    {
        if (_injectableValues == null) {
            reportBadDefinition(ClassUtil.classOf(valueId), String.format(
"No 'injectableValues' configured, cannot inject value with id [%s]", valueId));
        }
        return _injectableValues.findInjectableValue(valueId, this, forProperty, beanInstance);
    }

    /**
     * Convenience method for accessing the default Base64 encoding
     * used for decoding base64 encoded binary content.
     * Same as calling:
     *<pre>
     *  getConfig().getBase64Variant();
     *</pre>
     */
    public final Base64Variant getBase64Variant() {
        return _config.getBase64Variant();
    }

    /**
     * Convenience method, functionally equivalent to:
     *<pre>
     *  getConfig().getNodeFactory();
     * </pre>
     */
    public final JsonNodeFactory getNodeFactory() {
        return _config.getNodeFactory();
    }

    /*
    /**********************************************************
    /* Public API, pass-through to DeserializerCache
    /**********************************************************
     */

    /**
     * Method for checking whether we could find a deserializer
     * for given type.
     *
     * @param type
     * @since 2.3
     */
    public boolean hasValueDeserializerFor(JavaType type, AtomicReference<Throwable> cause) {
        try {
            return _cache.hasValueDeserializerFor(this, _factory, type);
        } catch (JsonMappingException e) {
            if (cause != null) {
                cause.set(e);
            }
        } catch (RuntimeException e) {
            if (cause == null) { // earlier behavior
                throw e;
            }
            cause.set(e);
        }
        return false;
    }
    
    /**
     * Method for finding a value deserializer, and creating a contextual
     * version if necessary, for value reached via specified property.
     */
    @SuppressWarnings("unchecked")
    public final JsonDeserializer<Object> findContextualValueDeserializer(JavaType type,
            BeanProperty prop) throws JsonMappingException
    {
        JsonDeserializer<Object> deser = _cache.findValueDeserializer(this, _factory, type);
        if (deser != null) {
            deser = (JsonDeserializer<Object>) handleSecondaryContextualization(deser, prop, type);
        }
        return deser;
    }

    /**
     * Variant that will try to locate deserializer for current type, but without
     * performing any contextualization (unlike {@link #findContextualValueDeserializer})
     * or checking for need to create a {@link TypeDeserializer} (unlike
     * {@link #findRootValueDeserializer(JavaType)}.
     * This method is usually called from within {@link ResolvableDeserializer#resolve},
     * and expectation is that caller then calls either
     * {@link #handlePrimaryContextualization(JsonDeserializer, BeanProperty, JavaType)} or
     * {@link #handleSecondaryContextualization(JsonDeserializer, BeanProperty, JavaType)} at a
     * later point, as necessary.
     *
     * @since 2.5
     */
    public final JsonDeserializer<Object> findNonContextualValueDeserializer(JavaType type)
        throws JsonMappingException
    {
        return _cache.findValueDeserializer(this, _factory, type);
    }
    
    /**
     * Method for finding a deserializer for root-level value.
     */
    @SuppressWarnings("unchecked")
    public final JsonDeserializer<Object> findRootValueDeserializer(JavaType type)
        throws JsonMappingException
    {
        JsonDeserializer<Object> deser = _cache.findValueDeserializer(this,
                _factory, type);
        if (deser == null) { // can this occur?
            return null;
        }
        deser = (JsonDeserializer<Object>) handleSecondaryContextualization(deser, null, type);
        TypeDeserializer typeDeser = _factory.findTypeDeserializer(_config, type);
        if (typeDeser != null) {
            // important: contextualize to indicate this is for root value
            typeDeser = typeDeser.forProperty(null);
            return new TypeWrappedDeserializer(typeDeser, deser);
        }
        return deser;
    }

    /**
     * Convenience method, functionally same as:
     *<pre>
     *  getDeserializerProvider().findKeyDeserializer(getConfig(), prop.getType(), prop);
     *</pre>
     */
    public final KeyDeserializer findKeyDeserializer(JavaType keyType,
            BeanProperty prop) throws JsonMappingException {
        KeyDeserializer kd = _cache.findKeyDeserializer(this,
                _factory, keyType);
        // Second: contextualize?
        if (kd instanceof ContextualKeyDeserializer) {
            kd = ((ContextualKeyDeserializer) kd).createContextual(this, prop);
        }
        return kd;
    }
    
    /*
    /**********************************************************
    /* Public API, ObjectId handling
    /**********************************************************
     */

    /**
     * Method called to find and return entry corresponding to given
     * Object Id: will add an entry if necessary, and never returns null
     */
    public abstract ReadableObjectId findObjectId(Object id, ObjectIdGenerator<?> generator, ObjectIdResolver resolver);

    /**
     * Method called to ensure that every object id encounter during processing
     * are resolved.
     * 
     * @throws UnresolvedForwardReference
     */
    public abstract void checkUnresolvedObjectId()
        throws UnresolvedForwardReference;

    /*
    /**********************************************************
    /* Public API, type handling
    /**********************************************************
     */
    
    /**
     * Convenience method, functionally equivalent to:
     *<pre>
     *  getConfig().constructType(cls);
     * </pre>
     */
    public final JavaType constructType(Class<?> cls) {
        return (cls == null) ? null : _config.constructType(cls);
    }

    /**
     * Helper method that is to be used when resolving basic class name into
     * Class instance, the reason being that it may be necessary to work around
     * various ClassLoader limitations, as well as to handle primitive type
     * signatures.
     *
     * @since 2.6
     */
    public Class<?> findClass(String className) throws ClassNotFoundException
    {
        // By default, delegate to ClassUtil: can be overridden with custom handling
        return getTypeFactory().findClass(className);
    }

    /*
    /**********************************************************
    /* Public API, helper object recycling
    /**********************************************************
     */

    /**
     * Method that can be used to get access to a reusable ObjectBuffer,
     * useful for efficiently constructing Object arrays and Lists.
     * Note that leased buffers should be returned once deserializer
     * is done, to allow for reuse during same round of deserialization.
     */
    public final ObjectBuffer leaseObjectBuffer()
    {
        ObjectBuffer buf = _objectBuffer;
        if (buf == null) {
            buf = new ObjectBuffer();
        } else {
            _objectBuffer = null;
        }
        return buf;
    }

    /**
     * Method to call to return object buffer previously leased with
     * {@link #leaseObjectBuffer}.
     * 
     * @param buf Returned object buffer
     */
    public final void returnObjectBuffer(ObjectBuffer buf)
    {
        /* Already have a reusable buffer? Let's retain bigger one
         * (or if equal, favor newer one, shorter life-cycle)
         */
        if (_objectBuffer == null
            || buf.initialCapacity() >= _objectBuffer.initialCapacity()) {
            _objectBuffer = buf;
        }
    }

    /**
     * Method for accessing object useful for building arrays of
     * primitive types (such as int[]).
     */
    public final ArrayBuilders getArrayBuilders()
    {
        if (_arrayBuilders == null) {
            _arrayBuilders = new ArrayBuilders();
        }
        return _arrayBuilders;
    }

    /*
    /**********************************************************
    /* Extended API: handler instantiation
    /**********************************************************
     */

    public abstract JsonDeserializer<Object> deserializerInstance(Annotated annotated,
            Object deserDef)
        throws JsonMappingException;

    public abstract KeyDeserializer keyDeserializerInstance(Annotated annotated,
            Object deserDef)
        throws JsonMappingException;

    /*
    /**********************************************************
    /* Extended API: resolving contextual deserializers; called
    /* by structured deserializers for their value/component
    /* deserializers
    /**********************************************************
     */

    /**
     * Method called for primary property deserializers (ones
     * directly created to deserialize values of a POJO property),
     * to handle details of resolving
     * {@link ContextualDeserializer} with given property context.
     * 
     * @param prop Property for which the given primary deserializer is used; never null.
     * 
     * @since 2.5
     */
    public JsonDeserializer<?> handlePrimaryContextualization(JsonDeserializer<?> deser,
            BeanProperty prop, JavaType type)
        throws JsonMappingException
    {
        if (deser instanceof ContextualDeserializer) {
            _currentType = new LinkedNode<JavaType>(type, _currentType);
            try {
                deser = ((ContextualDeserializer) deser).createContextual(this, prop);
            } finally {
                _currentType = _currentType.next();
            }
        }
        return deser;
    }

    /**
     * Method called for secondary property deserializers (ones
     * NOT directly created to deal with an annotatable POJO property,
     * but instead created as a component -- such as value deserializers
     * for structured types, or deserializers for root values)
     * to handle details of resolving
     * {@link ContextualDeserializer} with given property context.
     * Given that these deserializers are not directly related to given property
     * (or, in case of root value property, to any property), annotations
     * accessible may or may not be relevant.
     * 
     * @param prop Property for which deserializer is used, if any; null
     *    when deserializing root values
     * 
     * @since 2.5
     */
    public JsonDeserializer<?> handleSecondaryContextualization(JsonDeserializer<?> deser,
            BeanProperty prop, JavaType type)
        throws JsonMappingException
    {
        if (deser instanceof ContextualDeserializer) {
            _currentType = new LinkedNode<JavaType>(type, _currentType);
            try {
                deser = ((ContextualDeserializer) deser).createContextual(this, prop);
            } finally {
                _currentType = _currentType.next();
            }
        }
        return deser;
    }

    /*
    /**********************************************************
    /* Parsing methods that may use reusable/-cyclable objects
    /**********************************************************
     */

    /**
     * Convenience method for parsing a Date from given String, using
     * currently configured date format (accessed using
     * {@link DeserializationConfig#getDateFormat()}).
     *<p>
     * Implementation will handle thread-safety issues related to
     * date formats such that first time this method is called,
     * date format is cloned, and cloned instance will be retained
     * for use during this deserialization round.
     */
    public Date parseDate(String dateStr) throws IllegalArgumentException
    {
        try {
            DateFormat df = getDateFormat();
            return df.parse(dateStr);
        } catch (ParseException e) {
            throw new IllegalArgumentException(String.format(
                    "Failed to parse Date value '%s': %s", dateStr,
                    ClassUtil.exceptionMessage(e)));
        }
    }

    /**
     * Convenience method for constructing Calendar instance set
     * to specified time, to be modified and used by caller.
     */
    public Calendar constructCalendar(Date d) {
        // 08-Jan-2008, tatu: not optimal, but should work for the most part; let's revise as needed.
        Calendar c = Calendar.getInstance(getTimeZone());
        c.setTime(d);
        return c;
    }

    /*
    /**********************************************************
    /* Convenience methods for reading parsed values
    /**********************************************************
     */

    /**
     * Convenience method that may be used by composite or container deserializers,
     * for reading one-off values contained (for sequences, it is more efficient
     * to actually fetch deserializer once for the whole collection).
     *<p>
     * NOTE: when deserializing values of properties contained in composite types,
     * rather use {@link #readPropertyValue(JsonParser, BeanProperty, Class)};
     * this method does not allow use of contextual annotations.
     * 
     * @since 2.4
     */
    public <T> T readValue(JsonParser p, Class<T> type) throws IOException {
        return readValue(p, getTypeFactory().constructType(type));
    }

    /**
     * @since 2.4
     */
    @SuppressWarnings("unchecked")
    public <T> T readValue(JsonParser p, JavaType type) throws IOException {
        JsonDeserializer<Object> deser = findRootValueDeserializer(type);
        if (deser == null) {
            reportBadDefinition(type,
                    "Could not find JsonDeserializer for type "+type);
        }
        return (T) deser.deserialize(p, this);
    }

    /**
     * Convenience method that may be used by composite or container deserializers,
     * for reading one-off values for the composite type, taking into account
     * annotations that the property (passed to this method -- usually property that
     * has custom serializer that called this method) has.
     * 
     * @since 2.4
     */
    public <T> T readPropertyValue(JsonParser p, BeanProperty prop, Class<T> type) throws IOException {
        return readPropertyValue(p, prop, getTypeFactory().constructType(type));
    }

    /**
     * @since 2.4
     */
    @SuppressWarnings("unchecked")
    public <T> T readPropertyValue(JsonParser p, BeanProperty prop, JavaType type) throws IOException {
        JsonDeserializer<Object> deser = findContextualValueDeserializer(type, prop);
        if (deser == null) {
            return reportBadDefinition(type, String.format(
                    "Could not find JsonDeserializer for type %s (via property %s)",
                    type, ClassUtil.nameOf(prop)));
        }
        return (T) deser.deserialize(p, this);
    }

    /*
    /**********************************************************
    /* Methods for problem handling
    /**********************************************************
     */

    /**
     * Method that deserializers should call if they encounter an unrecognized
     * property (and once that is not explicitly designed as ignorable), to
     * inform possibly configured {@link DeserializationProblemHandler}s and
     * let it handle the problem.
     * 
     * @return True if there was a configured problem handler that was able to handle the
     *   problem
     */
    public boolean handleUnknownProperty(JsonParser p, JsonDeserializer<?> deser,
            Object instanceOrClass, String propName)
        throws IOException
    {
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            // Can bail out if it's handled
            if (h.value().handleUnknownProperty(this, p, deser, instanceOrClass, propName)) {
                return true;
            }
            h = h.next();
        }
        // Nope, not handled. Potentially that's a problem...
        if (!isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
            p.skipChildren();
            return true;
        }
        // Do we know properties that are expected instead?
        Collection<Object> propIds = (deser == null) ? null : deser.getKnownPropertyNames();
        throw UnrecognizedPropertyException.from(_parser,
                instanceOrClass, propName, propIds);
    }

    /**
     * Method that deserializers should call if they encounter a String value
     * that cannot be converted to expected key of a {@link java.util.Map}
     * valued property.
     * Default implementation will try to call {@link DeserializationProblemHandler#handleWeirdNumberValue}
     * on configured handlers, if any, to allow for recovery; if recovery does not
     * succeed, will throw {@link InvalidFormatException} with given message.
     *
     * @param keyClass Expected type for key
     * @param keyValue String value from which to deserialize key
     * @param msg Error message template caller wants to use if exception is to be thrown
     * @param msgArgs Optional arguments to use for message, if any
     *
     * @return Key value to use
     *
     * @throws IOException To indicate unrecoverable problem, usually based on <code>msg</code>
     * 
     * @since 2.8
     */
    public Object handleWeirdKey(Class<?> keyClass, String keyValue,
            String msg, Object... msgArgs)
        throws IOException
    {
        // but if not handled, just throw exception
        msg = _format(msg, msgArgs);
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            // Can bail out if it's handled
            Object key = h.value().handleWeirdKey(this, keyClass, keyValue, msg);
            if (key != DeserializationProblemHandler.NOT_HANDLED) {
                // Sanity check for broken handlers, otherwise nasty to debug:
                if ((key == null) || keyClass.isInstance(key)) {
                    return key;
                }
                throw weirdStringException(keyValue, keyClass, String.format(
                        "DeserializationProblemHandler.handleWeirdStringValue() for type %s returned value of type %s",
                        keyClass, key.getClass()));
            }
            h = h.next();
        }
        throw weirdKeyException(keyClass, keyValue, msg);
    }

    /**
     * Method that deserializers should call if they encounter a String value
     * that cannot be converted to target property type, in cases where some
     * String values could be acceptable (either with different strategySettings,
     * or different value).
     * Default implementation will try to call {@link DeserializationProblemHandler#handleWeirdStringValue}
     * on configured handlers, if any, to allow for recovery; if recovery does not
     * succeed, will throw {@link InvalidFormatException} with given message.
     *
     * @param targetClass Type of property into which incoming number should be converted
     * @param value String value from which to deserialize property value
     * @param msg Error message template caller wants to use if exception is to be thrown
     * @param msgArgs Optional arguments to use for message, if any
     *
     * @return Property value to use
     *
     * @throws IOException To indicate unrecoverable problem, usually based on <code>msg</code>
     * 
     * @since 2.8
     */
    public Object handleWeirdStringValue(Class<?> targetClass, String value,
            String msg, Object... msgArgs)
        throws IOException
    {
        // but if not handled, just throw exception
        msg = _format(msg, msgArgs);
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            // Can bail out if it's handled
            Object instance = h.value().handleWeirdStringValue(this, targetClass, value, msg);
            if (instance != DeserializationProblemHandler.NOT_HANDLED) {
                // Sanity check for broken handlers, otherwise nasty to debug:
                if (_isCompatible(targetClass, instance)) {
                    return instance;
                }
                throw weirdStringException(value, targetClass, String.format(
                        "DeserializationProblemHandler.handleWeirdStringValue() for type %s returned value of type %s",
                        targetClass, instance.getClass()));
            }
            h = h.next();
        }
        throw weirdStringException(value, targetClass, msg);
    }

    /**
     * Method that deserializers should call if they encounter a numeric value
     * that cannot be converted to target property type, in cases where some
     * numeric values could be acceptable (either with different strategySettings,
     * or different numeric value).
     * Default implementation will try to call {@link DeserializationProblemHandler#handleWeirdNumberValue}
     * on configured handlers, if any, to allow for recovery; if recovery does not
     * succeed, will throw {@link InvalidFormatException} with given message.
     *
     * @param targetClass Type of property into which incoming number should be converted
     * @param value Number value from which to deserialize property value
     * @param msg Error message template caller wants to use if exception is to be thrown
     * @param msgArgs Optional arguments to use for message, if any
     *
     * @return Property value to use
     *
     * @throws IOException To indicate unrecoverable problem, usually based on <code>msg</code>
     * 
     * @since 2.8
     */
    public Object handleWeirdNumberValue(Class<?> targetClass, Number value,
            String msg, Object... msgArgs)
        throws IOException
    {
        msg = _format(msg, msgArgs);
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            // Can bail out if it's handled
            Object key = h.value().handleWeirdNumberValue(this, targetClass, value, msg);
            if (key != DeserializationProblemHandler.NOT_HANDLED) {
                // Sanity check for broken handlers, otherwise nasty to debug:
                if (_isCompatible(targetClass, key)) {
                    return key;
                }
                throw weirdNumberException(value, targetClass, _format(
                        "DeserializationProblemHandler.handleWeirdNumberValue() for type %s returned value of type %s",
                        targetClass, key.getClass()));
            }
            h = h.next();
        }
        throw weirdNumberException(value, targetClass, msg);
    }

    public Object handleWeirdNativeValue(JavaType targetType, Object badValue,
            JsonParser p)
        throws IOException
    {
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        final Class<?> raw = targetType.getRawClass();
        for (; h != null; h = h.next()) {
            // Can bail out if it's handled
            Object goodValue = h.value().handleWeirdNativeValue(this, targetType, badValue, p);
            if (goodValue != DeserializationProblemHandler.NOT_HANDLED) {
                // Sanity check for broken handlers, otherwise nasty to debug:
                if ((goodValue == null) || raw.isInstance(goodValue)) {
                    return goodValue;
                }
                throw JsonMappingException.from(p, _format(
"DeserializationProblemHandler.handleWeirdNativeValue() for type %s returned value of type %s",
targetType, goodValue.getClass()));
            }
        }
        throw weirdNativeValueException(badValue, raw);
    }

    /**
     * Method that deserializers should call if they fail to instantiate value
     * due to lack of viable instantiator (usually creator, that is, constructor
     * or static factory method). Method should be called at point where value
     * has not been decoded, so that handler has a chance to handle decoding
     * using alternate mechanism, and handle underlying content (possibly by
     * just skipping it) to keep input state valid
     *
     * @param instClass Type that was to be instantiated
     * @param valueInst (optional) Value instantiator to be used, if any; null if type does not
     *    use one for instantiation (custom deserialiers don't; standard POJO deserializer does)
     * @param p Parser that points to the JSON value to decode
     *
     * @return Object that should be constructed, if any; has to be of type <code>instClass</code>
     *
     * @since 2.9 (2.8 had alternate that did not take <code>ValueInstantiator</code>)
     */
    @SuppressWarnings("resource")
    public Object handleMissingInstantiator(Class<?> instClass, ValueInstantiator valueInst,
            JsonParser p, String msg, Object... msgArgs)
        throws IOException
    {
        if (p == null) {
            p = getParser();
        }
        msg = _format(msg, msgArgs);
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            // Can bail out if it's handled
            Object instance = h.value().handleMissingInstantiator(this,
                    instClass, valueInst, p, msg);
            if (instance != DeserializationProblemHandler.NOT_HANDLED) {
                // Sanity check for broken handlers, otherwise nasty to debug:
                if (_isCompatible(instClass, instance)) {
                    return instance;
                }
                reportBadDefinition(constructType(instClass), String.format(
"DeserializationProblemHandler.handleMissingInstantiator() for type %s returned value of type %s",
                        instClass, ClassUtil.classNameOf(instance)));
            }
            h = h.next();
        }

        // 16-Oct-2016, tatu: This is either a definition problem (if no applicable creator
        //   exists), or input mismatch problem (otherwise) since none of existing creators
        //   match with token.
        if ((valueInst != null) && !valueInst.canInstantiate()) {
            msg = String.format("Cannot construct instance of %s (no Creators, like default construct, exist): %s",
                    ClassUtil.nameOf(instClass), msg);
            return reportBadDefinition(constructType(instClass), msg);
        }
        msg = String.format("Cannot construct instance of %s (although at least one Creator exists): %s",
                ClassUtil.nameOf(instClass), msg);
        return reportInputMismatch(instClass, msg);
    }

    /**
     * Method that deserializers should call if they fail to instantiate value
     * due to an exception that was thrown by constructor (or other mechanism used
     * to create instances).
     * Default implementation will try to call {@link DeserializationProblemHandler#handleInstantiationProblem}
     * on configured handlers, if any, to allow for recovery; if recovery does not
     * succeed, will throw exception constructed with {@link #instantiationException}.
     *
     * @param instClass Type that was to be instantiated
     * @param argument (optional) Argument that was passed to constructor or equivalent
     *    instantiator; often a {@link java.lang.String}.
     * @param t Exception that caused failure
     *
     * @return Object that should be constructed, if any; has to be of type <code>instClass</code>
     *
     * @since 2.8
     */
    public Object handleInstantiationProblem(Class<?> instClass, Object argument,
            Throwable t)
        throws IOException
    {
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            // Can bail out if it's handled
            Object instance = h.value().handleInstantiationProblem(this, instClass, argument, t);
            if (instance != DeserializationProblemHandler.NOT_HANDLED) {
                // Sanity check for broken handlers, otherwise nasty to debug:
                if (_isCompatible(instClass, instance)) {
                    return instance;
                }
                reportBadDefinition(constructType(instClass), String.format(
"DeserializationProblemHandler.handleInstantiationProblem() for type %s returned value of type %s",
                        instClass, ClassUtil.classNameOf(instance)));
            }
            h = h.next();
        }
        // 18-May-2016, tatu: Only wrap if not already a valid type to throw
        ClassUtil.throwIfIOE(t);
        throw instantiationException(instClass, t);
    }

    /**
     * Method that deserializers should call if the first token of the value to
     * deserialize is of unexpected type (that is, type of token that deserializer
     * cannot handle). This could occur, for example, if a Number deserializer
     * encounter {@link JsonToken#START_ARRAY} instead of
     * {@link JsonToken#VALUE_NUMBER_INT} or {@link JsonToken#VALUE_NUMBER_FLOAT}.
     * 
     * @param instClass Type that was to be instantiated
     * @param p Parser that points to the JSON value to decode
     *
     * @return Object that should be constructed, if any; has to be of type <code>instClass</code>
     *
     * @since 2.8
     */
    public Object handleUnexpectedToken(Class<?> instClass, JsonParser p)
        throws IOException
    {
        return handleUnexpectedToken(instClass, p.getCurrentToken(), p, null);
    }

    /**
     * Method that deserializers should call if the first token of the value to
     * deserialize is of unexpected type (that is, type of token that deserializer
     * cannot handle). This could occur, for example, if a Number deserializer
     * encounter {@link JsonToken#START_ARRAY} instead of
     * {@link JsonToken#VALUE_NUMBER_INT} or {@link JsonToken#VALUE_NUMBER_FLOAT}.
     * 
     * @param instClass Type that was to be instantiated
     * @param t Token encountered that does match expected
     * @param p Parser that points to the JSON value to decode
     *
     * @return Object that should be constructed, if any; has to be of type <code>instClass</code>
     *
     * @since 2.8
     */
    public Object handleUnexpectedToken(Class<?> instClass, JsonToken t,
            JsonParser p, String msg, Object... msgArgs)
        throws IOException
    {
        msg = _format(msg, msgArgs);
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            Object instance = h.value().handleUnexpectedToken(this,
                    instClass, t, p, msg);
            if (instance != DeserializationProblemHandler.NOT_HANDLED) {
                if (_isCompatible(instClass, instance)) {
                    return instance;
                }
                reportBadDefinition(constructType(instClass), String.format(
                        "DeserializationProblemHandler.handleUnexpectedToken() for type %s returned value of type %s",
                        ClassUtil.nameOf(instClass), ClassUtil.classNameOf(instance)));
            }
            h = h.next();
        }
        if (msg == null) {
            if (t == null) {
                msg = String.format("Unexpected end-of-input when binding data into %s",
                        ClassUtil.nameOf(instClass));
            } else {
                msg = String.format("Cannot deserialize instance of %s out of %s token",
                        ClassUtil.nameOf(instClass), t);
            }
        }
        reportInputMismatch(instClass, msg);
        return null; // never gets here
    }

    /**
     * Method that deserializers should call if they encounter a type id
     * (for polymorphic deserialization) that cannot be resolved to an
     * actual type; usually since there is no mapping defined.
     * Default implementation will try to call {@link DeserializationProblemHandler#handleUnknownTypeId}
     * on configured handlers, if any, to allow for recovery; if recovery does not
     * succeed, will throw exception constructed with {@link #invalidTypeIdException}.
     *
     * @param baseType Base type from which resolution starts
     * @param id Type id that could not be converted
     * @param extraDesc Additional problem description to add to default exception message,
     *    if resolution fails.
     *
     * @return {@link JavaType} that id resolves to
     *
     * @throws IOException To indicate unrecoverable problem, if resolution cannot
     *    be made to work
     *
     * @since 2.8
     */
    public JavaType handleUnknownTypeId(JavaType baseType, String id,
            TypeIdResolver idResolver, String extraDesc) throws IOException
    {
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            // Can bail out if it's handled
            JavaType type = h.value().handleUnknownTypeId(this, baseType, id, idResolver, extraDesc);
            if (type != null) {
                if (type.hasRawClass(Void.class)) {
                    return null;
                }
                // But ensure there's type compatibility
                if (type.isTypeOrSubTypeOf(baseType.getRawClass())) {
                    return type;
                }
                throw invalidTypeIdException(baseType, id,
                        "problem handler tried to resolve into non-subtype: "+type);
            }
            h = h.next();
        }
        // 24-May-2016, tatu: Actually we may still not want to fail quite yet
        if (!isEnabled(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)) {
            return null;
        }
        throw invalidTypeIdException(baseType, id, extraDesc);
    }

    /**
     * @since 2.9
     */
    public JavaType handleMissingTypeId(JavaType baseType,
            TypeIdResolver idResolver, String extraDesc) throws IOException
    {
        LinkedNode<DeserializationProblemHandler> h = _config.getProblemHandlers();
        while (h != null) {
            // Can bail out if it's handled
            JavaType type = h.value().handleMissingTypeId(this, baseType, idResolver, extraDesc);
            if (type != null) {
                if (type.hasRawClass(Void.class)) {
                    return null;
                }
                // But ensure there's type compatibility
                if (type.isTypeOrSubTypeOf(baseType.getRawClass())) {
                    return type;
                }
                throw invalidTypeIdException(baseType, null,
                        "problem handler tried to resolve into non-subtype: "+type);
            }
            h = h.next();
        }
        // 09-Mar-2017, tatu: We may want to consider yet another feature at some
        //    point to allow returning `null`... but that seems bit risky for now
//        if (!isEnabled(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)) {
//            return null;
//        }
        throw missingTypeIdException(baseType, extraDesc);
    }

    /**
     * @since 2.9.2
     */
    protected boolean _isCompatible(Class<?> target, Object value)
    {
        if ((value == null) || target.isInstance(value)) {
            return true;
        }
        // [databind#1767]: Make sure to allow wrappers for primitive fields
        return target.isPrimitive()
                && ClassUtil.wrapperType(target).isInstance(value);
    }

    /*
    /**********************************************************
    /* Methods for problem reporting, in cases where recovery
    /* is not considered possible: input problem
    /**********************************************************
     */

    /**
     * Method for deserializers to call 
     * when the token encountered was of type different than what <b>should</b>
     * be seen at that position, usually within a sequence of expected tokens.
     * Note that this method will throw a {@link JsonMappingException} and no
     * recovery is attempted (via {@link DeserializationProblemHandler}, as
     * problem is considered to be difficult to recover from, in general.
     * 
     * @since 2.9
     */
    public void reportWrongTokenException(JsonDeserializer<?> deser,
            JsonToken expToken, String msg, Object... msgArgs)
        throws JsonMappingException
    {
        msg = _format(msg, msgArgs);
        throw wrongTokenException(getParser(), deser.handledType(), expToken, msg);
    }
    
    /**
     * Method for deserializers to call 
     * when the token encountered was of type different than what <b>should</b>
     * be seen at that position, usually within a sequence of expected tokens.
     * Note that this method will throw a {@link JsonMappingException} and no
     * recovery is attempted (via {@link DeserializationProblemHandler}, as
     * problem is considered to be difficult to recover from, in general.
     * 
     * @since 2.9
     */
    public void reportWrongTokenException(JavaType targetType,
            JsonToken expToken, String msg, Object... msgArgs)
        throws JsonMappingException
    {
        msg = _format(msg, msgArgs);
        throw wrongTokenException(getParser(), targetType, expToken, msg);
    }

    /**
     * Method for deserializers to call 
     * when the token encountered was of type different than what <b>should</b>
     * be seen at that position, usually within a sequence of expected tokens.
     * Note that this method will throw a {@link JsonMappingException} and no
     * recovery is attempted (via {@link DeserializationProblemHandler}, as
     * problem is considered to be difficult to recover from, in general.
     * 
     * @since 2.9
     */
    public void reportWrongTokenException(Class<?> targetType,
            JsonToken expToken, String msg, Object... msgArgs)
        throws JsonMappingException
    {
        msg = _format(msg, msgArgs);
        throw wrongTokenException(getParser(), targetType, expToken, msg);
    }

    /**
     * @since 2.8
     */
    public <T> T reportUnresolvedObjectId(ObjectIdReader oidReader, Object bean)
        throws JsonMappingException
    {
        String msg = String.format("No Object Id found for an instance of %s, to assign to property '%s'",
                ClassUtil.classNameOf(bean), oidReader.propertyName);
        return reportInputMismatch(oidReader.idProperty, msg);
    }

    /**
     * Helper method used to indicate a problem with input in cases where more
     * specific <code>reportXxx()</code> method was not available.
     *
     * @since 2.9
     */
    public <T> T reportInputMismatch(BeanProperty prop,
            String msg, Object... msgArgs) throws JsonMappingException
    {
        msg = _format(msg, msgArgs);
        JavaType type = (prop == null) ? null : prop.getType();
        throw MismatchedInputException.from(getParser(), type, msg);
    }

    /**
     * Helper method used to indicate a problem with input in cases where more
     * specific <code>reportXxx()</code> method was not available.
     *
     * @since 2.9
     */
    public <T> T reportInputMismatch(JsonDeserializer<?> src,
            String msg, Object... msgArgs) throws JsonMappingException
    {
        msg = _format(msg, msgArgs);
        throw MismatchedInputException.from(getParser(), src.handledType(), msg);
    }

    /**
     * Helper method used to indicate a problem with input in cases where more
     * specific <code>reportXxx()</code> method was not available.
     *
     * @since 2.9
     */
    public <T> T reportInputMismatch(Class<?> targetType,
            String msg, Object... msgArgs) throws JsonMappingException
    {
        msg = _format(msg, msgArgs);
        throw MismatchedInputException.from(getParser(), targetType, msg);
    }

    /**
     * Helper method used to indicate a problem with input in cases where more
     * specific <code>reportXxx()</code> method was not available.
     *
     * @since 2.9
     */
    public <T> T reportInputMismatch(JavaType targetType,
            String msg, Object... msgArgs) throws JsonMappingException
    {
        msg = _format(msg, msgArgs);
        throw MismatchedInputException.from(getParser(), targetType, msg);
    }

    public <T> T reportTrailingTokens(Class<?> targetType,
            JsonParser p, JsonToken trailingToken) throws JsonMappingException
    {
        throw MismatchedInputException.from(p, targetType, String.format(
"Trailing token (of type %s) found after value (bound as %s): not allowed as per `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`",
trailingToken, ClassUtil.nameOf(targetType)
                ));
    }

    @Deprecated // since 2.9
    public void reportWrongTokenException(JsonParser p,
            JsonToken expToken, String msg, Object... msgArgs)
        throws JsonMappingException
    {
        msg = _format(msg, msgArgs);
        throw wrongTokenException(p, expToken, msg);
    }
    
    /**
     * Helper method for reporting a problem with unhandled unknown property.
     * 
     * @param instanceOrClass Either value being populated (if one has been
     *   instantiated), or Class that indicates type that would be (or
     *   have been) instantiated
     * @param deser Deserializer that had the problem, if called by deserializer
     *   (or on behalf of one)
     *
     * @deprecated Since 2.8 call {@link #handleUnknownProperty} instead
     */
    @Deprecated
    public void reportUnknownProperty(Object instanceOrClass, String fieldName,
            JsonDeserializer<?> deser)
        throws JsonMappingException
    {
        if (isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
            // Do we know properties that are expected instead?
            Collection<Object> propIds = (deser == null) ? null : deser.getKnownPropertyNames();
            throw UnrecognizedPropertyException.from(_parser,
                    instanceOrClass, fieldName, propIds);
        }
    }

    /**
     * @since 2.8
     *
     * @deprecated Since 2.9: not clear this ever occurs
     */
    @Deprecated // since 2.9
    public void reportMissingContent(String msg, Object... msgArgs) throws JsonMappingException {
        throw MismatchedInputException.from(getParser(), (JavaType) null, "No content to map due to end-of-input");
    }

    /*
    /**********************************************************
    /* Methods for problem reporting, in cases where recovery
    /* is not considered possible: POJO definition problems
    /**********************************************************
     */
    
    /**
     * Helper method called to indicate problem in POJO (serialization) definitions or strategySettings
     * regarding specific Java type, unrelated to actual JSON content to map.
     * Default behavior is to construct and throw a {@link JsonMappingException}.
     *
     * @since 2.9
     */
    public <T> T reportBadTypeDefinition(BeanDescription bean,
            String msg, Object... msgArgs) throws JsonMappingException {
        msg = _format(msg, msgArgs);
        String beanDesc = ClassUtil.nameOf(bean.getBeanClass());
        msg = String.format("Invalid type definition for type %s: %s", beanDesc, msg);
        throw InvalidDefinitionException.from(_parser, msg, bean, null);
    }

    /**
     * Helper method called to indicate problem in POJO (serialization) definitions or strategySettings
     * regarding specific property (of a type), unrelated to actual JSON content to map.
     * Default behavior is to construct and throw a {@link JsonMappingException}.
     *
     * @since 2.9
     */
    public <T> T reportBadPropertyDefinition(BeanDescription bean, BeanPropertyDefinition prop,
            String msg, Object... msgArgs) throws JsonMappingException {
        msg = _format(msg, msgArgs);
        String propName = ClassUtil.nameOf(prop);
        String beanDesc = ClassUtil.nameOf(bean.getBeanClass());
        msg = String.format("Invalid definition for property %s (of type %s): %s",
                propName, beanDesc, msg);
        throw InvalidDefinitionException.from(_parser, msg, bean, prop);
    }

    @Override
    public <T> T reportBadDefinition(JavaType type, String msg) throws JsonMappingException {
        throw InvalidDefinitionException.from(_parser, msg, type);
    }

    /**
     * Method that deserializer may call if it is called to do an update ("merge")
     * but deserializer operates on a non-mergeable type. Although this should
     * usually be caught earlier, sometimes it may only be caught during operation
     * and if so this is the method to call.
     * Note that if {@link MapperFeature#IGNORE_MERGE_FOR_UNMERGEABLE} is enabled,
     * this method will simply return null; otherwise {@link InvalidDefinitionException}
     * will be thrown.
     *
     * @since 2.9
     */
    public <T> T reportBadMerge(JsonDeserializer<?> deser) throws JsonMappingException
    {
        if (isEnabled(MapperFeature.IGNORE_MERGE_FOR_UNMERGEABLE)) {
            return null;
        }
        JavaType type = constructType(deser.handledType());
        String msg = String.format("Invalid configuration: values of type %s cannot be merged", type);
        throw InvalidDefinitionException.from(getParser(), msg, type);
    }

    /*
    /**********************************************************
    /* Methods for constructing semantic exceptions; usually not
    /* to be called directly, call `handleXxx()` instead
    /**********************************************************
     */

    /**
     * Helper method for constructing {@link JsonMappingException} to indicate
     * that the token encountered was of type different than what <b>should</b>
     * be seen at that position, usually within a sequence of expected tokens.
     * Note that most of the time this method should NOT be directly called;
     * instead, {@link #reportWrongTokenException} should be called and will
     * call this method as necessary.
     *
     * @since 2.9
     */
    public JsonMappingException wrongTokenException(JsonParser p, JavaType targetType,
            JsonToken expToken, String extra)
    {
        String msg = String.format("Unexpected token (%s), expected %s",
                p.getCurrentToken(), expToken);
        msg = _colonConcat(msg, extra);
        return MismatchedInputException.from(p, targetType, msg);
    }

    public JsonMappingException wrongTokenException(JsonParser p, Class<?> targetType,
            JsonToken expToken, String extra)
    {
        String msg = String.format("Unexpected token (%s), expected %s",
                p.getCurrentToken(), expToken);
        msg = _colonConcat(msg, extra);
        return MismatchedInputException.from(p, targetType, msg);
    }
    
    @Deprecated // since 2.9
    public JsonMappingException wrongTokenException(JsonParser p, JsonToken expToken,
            String msg)
    {
        return wrongTokenException(p, (JavaType) null, expToken, msg);
    }

    /**
     * Helper method for constructing exception to indicate that given JSON
     * Object field name was not in format to be able to deserialize specified
     * key type.
     * Note that most of the time this method should NOT be called; instead,
     * {@link #handleWeirdKey} should be called which will call this method
     * if necessary.
     */
    public JsonMappingException weirdKeyException(Class<?> keyClass, String keyValue,
            String msg) {
        return InvalidFormatException.from(_parser,
                String.format("Cannot deserialize Map key of type %s from String %s: %s",
                        ClassUtil.nameOf(keyClass), _quotedString(keyValue), msg),
                keyValue, keyClass);
    }

    /**
     * Helper method for constructing exception to indicate that input JSON
     * String was not suitable for deserializing into given target type.
     * Note that most of the time this method should NOT be called; instead,
     * {@link #handleWeirdStringValue} should be called which will call this method
     * if necessary.
     * 
     * @param value String value from input being deserialized
     * @param instClass Type that String should be deserialized into
     * @param msg Message that describes specific problem
     * 
     * @since 2.1
     */
    public JsonMappingException weirdStringException(String value, Class<?> instClass,
            String msg) {
        return InvalidFormatException.from(_parser,
                String.format("Cannot deserialize value of type %s from String %s: %s",
                        ClassUtil.nameOf(instClass), _quotedString(value), msg),
                value, instClass);
    }

    /**
     * Helper method for constructing exception to indicate that input JSON
     * Number was not suitable for deserializing into given target type.
     * Note that most of the time this method should NOT be called; instead,
     * {@link #handleWeirdNumberValue} should be called which will call this method
     * if necessary.
     */
    public JsonMappingException weirdNumberException(Number value, Class<?> instClass,
            String msg) {
        return InvalidFormatException.from(_parser,
                String.format("Cannot deserialize value of type %s from number %s: %s",
                        ClassUtil.nameOf(instClass), String.valueOf(value), msg),
                value, instClass);
    }

    /**
     * Helper method for constructing exception to indicate that input JSON
     * token of type "native value" (see {@link JsonToken#VALUE_EMBEDDED_OBJECT})
     * is of incompatible type (and there is no delegating creator or such to use)
     * and can not be used to construct value of specified type (usually POJO).
     * Note that most of the time this method should NOT be called; instead,
     * {@link #handleWeirdNativeValue} should be called which will call this method
     *
     * @since 2.9
     */
    public JsonMappingException weirdNativeValueException(Object value, Class<?> instClass)
    {
        return InvalidFormatException.from(_parser, String.format(
"Cannot deserialize value of type %s from native value (`JsonToken.VALUE_EMBEDDED_OBJECT`) of type %s: incompatible types",
            ClassUtil.nameOf(instClass), ClassUtil.classNameOf(value)),
                value, instClass);
    }

    /**
     * Helper method for constructing instantiation exception for specified type,
     * to indicate problem with physically constructing instance of
     * specified class (missing constructor, exception from constructor)
     *<p>
     * Note that most of the time this method should NOT be called directly; instead,
     * {@link #handleInstantiationProblem} should be called which will call this method
     * if necessary.
     */
    public JsonMappingException instantiationException(Class<?> instClass, Throwable cause) {
        // Most likely problem with Creator definition, right?
        final JavaType type = constructType(instClass);
        String excMsg;
        if (cause == null) {
            excMsg = "N/A";
        } else if ((excMsg = ClassUtil.exceptionMessage(cause)) == null) {
            excMsg = ClassUtil.nameOf(cause.getClass());
        }
        String msg = String.format("Cannot construct instance of %s, problem: %s",
                ClassUtil.nameOf(instClass), excMsg);
        InvalidDefinitionException e = InvalidDefinitionException.from(_parser, msg, type);
        e.initCause(cause);
        return e;
    }

    /**
     * Helper method for constructing instantiation exception for specified type,
     * to indicate that instantiation failed due to missing instantiator
     * (creator; constructor or factory method).
     *<p>
     * Note that most of the time this method should NOT be called; instead,
     * {@link #handleMissingInstantiator} should be called which will call this method
     * if necessary.
     */
    public JsonMappingException instantiationException(Class<?> instClass, String msg0) {
        // Most likely problem with Creator definition, right?
        JavaType type = constructType(instClass);
        String msg = String.format("Cannot construct instance of %s: %s",
                ClassUtil.nameOf(instClass), msg0);
        return InvalidDefinitionException.from(_parser, msg, type);
    }

    @Override
    public JsonMappingException invalidTypeIdException(JavaType baseType, String typeId,
            String extraDesc) {
        String msg = String.format("Could not resolve type id '%s' as a subtype of %s",
                typeId, baseType);
        return InvalidTypeIdException.from(_parser, _colonConcat(msg, extraDesc), baseType, typeId);
    }

    /**
     * @since 2.9
     */
    public JsonMappingException missingTypeIdException(JavaType baseType,
            String extraDesc) {
        String msg = String.format("Missing type id when trying to resolve subtype of %s",
                baseType);
        return InvalidTypeIdException.from(_parser, _colonConcat(msg, extraDesc), baseType, null);
    }

    /*
    /**********************************************************
    /* Deprecated exception factory methods
    /**********************************************************
     */

    /**
     * @since 2.5
     *
     * @deprecated Since 2.8 use {@link #handleUnknownTypeId} instead
     */
    @Deprecated
    public JsonMappingException unknownTypeException(JavaType type, String id,
            String extraDesc)
    {
        String msg = String.format("Could not resolve type id '%s' into a subtype of %s",
                id, type);
        msg = _colonConcat(msg, extraDesc);
        return MismatchedInputException.from(_parser, type, msg);
    }

    /**
     * Helper method for constructing exception to indicate that end-of-input was
     * reached while still expecting more tokens to deserialize value of specified type.
     *
     * @deprecated Since 2.8; currently no way to catch EOF at databind level
     */
    @Deprecated
    public JsonMappingException endOfInputException(Class<?> instClass) {
        return MismatchedInputException.from(_parser, instClass,
                "Unexpected end-of-input when trying to deserialize a "+instClass.getName());
    }

    /*
    /**********************************************************
    /* Deprecated methods for constructing, throwing non-specific
    /* JsonMappingExceptions: as of 2.9, should use more specific
    /* ones.
    /**********************************************************
     */
    
    /**
     * Fallback method that may be called if no other <code>reportXxx</code>
     * is applicable -- but only in that case.
     *
     * @since 2.8
     * 
     * @deprecated Since 2.9: use a more specific method, or {@link #reportBadDefinition(JavaType, String)},
     *    or {@link #reportInputMismatch} instead
     */
    @Deprecated // since 2.9
    public void reportMappingException(String msg, Object... msgArgs)
        throws JsonMappingException
    {
        throw JsonMappingException.from(getParser(), _format(msg, msgArgs));
    }

    /**
     * Helper method for constructing generic mapping exception with specified
     * message and current location information.
     * Note that application code should almost always call
     * one of <code>handleXxx</code> methods, or {@link #reportMappingException(String, Object...)}
     * instead.
     * 
     * @since 2.6
     * 
     * @deprecated Since 2.9 use more specific error reporting methods instead
     */
    @Deprecated
    public JsonMappingException mappingException(String message) {
        return JsonMappingException.from(getParser(), message);
    }

    /**
     * Helper method for constructing generic mapping exception with specified
     * message and current location information
     * Note that application code should almost always call
     * one of <code>handleXxx</code> methods, or {@link #reportMappingException(String, Object...)}
     * instead.
     * 
     * @since 2.6
     *
     * @deprecated Since 2.9 use more specific error reporting methods instead
     */
    @Deprecated
    public JsonMappingException mappingException(String msg, Object... msgArgs) {
        return JsonMappingException.from(getParser(), _format(msg, msgArgs));
    }

    /**
     * Helper method for constructing generic mapping exception for specified type
     * 
     * @deprecated Since 2.8 use {@link #handleUnexpectedToken(Class, JsonParser)} instead
     */
    @Deprecated
    public JsonMappingException mappingException(Class<?> targetClass) {
        return mappingException(targetClass, _parser.getCurrentToken());
    }

    /**
     * @deprecated Since 2.8 use {@link #handleUnexpectedToken(Class, JsonParser)} instead
     */
    @Deprecated
    public JsonMappingException mappingException(Class<?> targetClass, JsonToken token) {
        return JsonMappingException.from(_parser,
                String.format("Cannot deserialize instance of %s out of %s token",
                        ClassUtil.nameOf(targetClass), token));
    }

    /*
    /**********************************************************
    /* Other internal methods
    /**********************************************************
     */

    protected DateFormat getDateFormat()
    {
        if (_dateFormat != null) {
            return _dateFormat;
        }
        /* 24-Feb-2012, tatu: At this point, all timezone configuration
         *    should have occurred, with respect to default dateformat
         *    and timezone configuration. But we still better clone
         *    an instance as formatters may be stateful.
         */
        DateFormat df = _config.getDateFormat();
        _dateFormat = df = (DateFormat) df.clone();
        return df;
    }
}
