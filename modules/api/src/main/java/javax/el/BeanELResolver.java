/*
 * Copyright 2006-2009 Odysseus Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package javax.el;

import java.beans.FeatureDescriptor;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.el.BeanELResolver.MethodDispatcher.Predicates.JuelCoercePredicat;

/**
 * Defines property resolution behavior on objects using the JavaBeans component architecture. This
 * resolver handles base objects of any type, as long as the base is not null. It accepts any object
 * as a property, and coerces it to a string. That string is then used to find a JavaBeans compliant
 * property on the base object. The value is accessed using JavaBeans getters and setters. This
 * resolver can be constructed in read-only mode, which means that isReadOnly will always return
 * true and {@link #setValue(ELContext, Object, Object, Object)} will always throw
 * PropertyNotWritableException. ELResolvers are combined together using {@link CompositeELResolver}
 * s, to define rich semantics for evaluating an expression. See the javadocs for {@link ELResolver}
 * for details. Because this resolver handles base objects of any type, it should be placed near the
 * end of a composite resolver. Otherwise, it will claim to have resolved a property before any
 * resolvers that come after it get a chance to test if they can do so as well.
 * 
 * @see CompositeELResolver
 * @see ELResolver
 */
public class BeanELResolver extends ELResolver {
	protected static final class BeanProperties {
		private final Map<String, BeanProperty> map = new HashMap<String, BeanProperty>();

		public BeanProperties(Class<?> baseClass) {
			PropertyDescriptor[] descriptors;
			try {
				descriptors = Introspector.getBeanInfo(baseClass).getPropertyDescriptors();
			} catch (IntrospectionException e) {
				throw new ELException(e);
			}
			for (PropertyDescriptor descriptor : descriptors) {
				map.put(descriptor.getName(), new BeanProperty(descriptor));
			}
		}

		public BeanProperty getBeanProperty(String property) {
			return map.get(property);
		}
	}

	protected static final class BeanProperty {
		private final PropertyDescriptor descriptor;
		
		private Method readMethod;
		private Method writedMethod;

		public BeanProperty(PropertyDescriptor descriptor) {
			this.descriptor = descriptor;
		}

		public Class<?> getPropertyType() {
			return descriptor.getPropertyType();
		}

		public Method getReadMethod() {
			if (readMethod == null) {
				readMethod = findAccessibleMethod(descriptor.getReadMethod());
			}
			return readMethod;
		}

		public Method getWriteMethod() {
			if (writedMethod == null) {
				writedMethod = findAccessibleMethod(descriptor.getWriteMethod());
			}
			return writedMethod;
		}

		public boolean isReadOnly() {
			return getWriteMethod() == null;
		}
	}

	private static Method findAccessibleMethod(Method method) {
		if (method == null || method.isAccessible()) {
			return method;
		}
		try {
			method.setAccessible(true);
		} catch (SecurityException e) {
			for (Class<?> cls : method.getDeclaringClass().getInterfaces()) {
				Method mth = null;
				try {
					mth = cls.getMethod(method.getName(), method.getParameterTypes());
					mth = findAccessibleMethod(mth);
					if (mth != null) {
						return mth;
					}
				} catch (NoSuchMethodException ignore) {
					// do nothing
				}
			}
			Class<?> cls = method.getDeclaringClass().getSuperclass();
			if (cls != null) {
				Method mth = null;
				try {
					mth = cls.getMethod(method.getName(), method.getParameterTypes());
					mth = findAccessibleMethod(mth);
					if (mth != null) {
						return mth;
					}
				} catch (NoSuchMethodException ignore) {
					// do nothing
				}
			}
			return null;
		}
		return method;
	}

	private final boolean readOnly;
	private final ConcurrentHashMap<Class<?>, BeanProperties> cache;
	
	private ExpressionFactory defaultFactory;

	/**
	 * Creates a new read/write BeanELResolver.
	 */
	public BeanELResolver() {
		this(false);
	}

	/**
	 * Creates a new BeanELResolver whose read-only status is determined by the given parameter.
	 */
	public BeanELResolver(boolean readOnly) {
		this.readOnly = readOnly;
		this.cache = new ConcurrentHashMap<Class<?>, BeanProperties>();
	}

	/**
	 * If the base object is not null, returns the most general type that this resolver accepts for
	 * the property argument. Otherwise, returns null. Assuming the base is not null, this method
	 * will always return Object.class. This is because any object is accepted as a key and is
	 * coerced into a string.
	 * 
	 * @param context
	 *            The context of this evaluation.
	 * @param base
	 *            The bean to analyze.
	 * @return null if base is null; otherwise Object.class.
	 */
	@Override
	public Class<?> getCommonPropertyType(ELContext context, Object base) {
		return isResolvable(base) ? Object.class : null;
	}

	/**
	 * If the base object is not null, returns an Iterator containing the set of JavaBeans
	 * properties available on the given object. Otherwise, returns null. The Iterator returned must
	 * contain zero or more instances of java.beans.FeatureDescriptor. Each info object contains
	 * information about a property in the bean, as obtained by calling the
	 * BeanInfo.getPropertyDescriptors method. The FeatureDescriptor is initialized using the same
	 * fields as are present in the PropertyDescriptor, with the additional required named
	 * attributes "type" and "resolvableAtDesignTime" set as follows:
	 * <ul>
	 * <li>{@link ELResolver#TYPE} - The runtime type of the property, from
	 * PropertyDescriptor.getPropertyType().</li>
	 * <li>{@link ELResolver#RESOLVABLE_AT_DESIGN_TIME} - true.</li>
	 * </ul>
	 * 
	 * @param context
	 *            The context of this evaluation.
	 * @param base
	 *            The bean to analyze.
	 * @return An Iterator containing zero or more FeatureDescriptor objects, each representing a
	 *         property on this bean, or null if the base object is null.
	 */
	@Override
	public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
		if (isResolvable(base)) {
			final PropertyDescriptor[] properties;
			try {
				properties = Introspector.getBeanInfo(base.getClass()).getPropertyDescriptors();
			} catch (IntrospectionException e) {
				return Collections.<FeatureDescriptor> emptyList().iterator();
			}
			return new Iterator<FeatureDescriptor>() {
				int next = 0;

				public boolean hasNext() {
					return properties != null && next < properties.length;
				}

				public FeatureDescriptor next() {
					PropertyDescriptor property = properties[next++];
					FeatureDescriptor feature = new FeatureDescriptor();
					feature.setDisplayName(property.getDisplayName());
					feature.setName(property.getName());
					feature.setShortDescription(property.getShortDescription());
					feature.setExpert(property.isExpert());
					feature.setHidden(property.isHidden());
					feature.setPreferred(property.isPreferred());
					feature.setValue(TYPE, property.getPropertyType());
					feature.setValue(RESOLVABLE_AT_DESIGN_TIME, true);
					return feature;
				}

				public void remove() {
					throw new UnsupportedOperationException("cannot remove");
				}
			};
		}
		return null;
	}

	/**
	 * If the base object is not null, returns the most general acceptable type that can be set on
	 * this bean property. If the base is not null, the propertyResolved property of the ELContext
	 * object must be set to true by this resolver, before returning. If this property is not true
	 * after this method is called, the caller should ignore the return value. The provided property
	 * will first be coerced to a String. If there is a BeanInfoProperty for this property and there
	 * were no errors retrieving it, the propertyType of the propertyDescriptor is returned.
	 * Otherwise, a PropertyNotFoundException is thrown.
	 * 
	 * @param context
	 *            The context of this evaluation.
	 * @param base
	 *            The bean to analyze.
	 * @param property
	 *            The name of the property to analyze. Will be coerced to a String.
	 * @return If the propertyResolved property of ELContext was set to true, then the most general
	 *         acceptable type; otherwise undefined.
	 * @throws NullPointerException
	 *             if context is null
	 * @throws PropertyNotFoundException
	 *             if base is not null and the specified property does not exist or is not readable.
	 * @throws ELException
	 *             if an exception was thrown while performing the property or variable resolution.
	 *             The thrown exception must be included as the cause property of this exception, if
	 *             available.
	 */
	@Override
	public Class<?> getType(ELContext context, Object base, Object property) {
		if (context == null) {
			throw new NullPointerException();
		}
		Class<?> result = null;
		if (isResolvable(base)) {
			result = toBeanProperty(base, property).getPropertyType();
			context.setPropertyResolved(true);
		}
		return result;
	}

	/**
	 * If the base object is not null, returns the current value of the given property on this bean.
	 * If the base is not null, the propertyResolved property of the ELContext object must be set to
	 * true by this resolver, before returning. If this property is not true after this method is
	 * called, the caller should ignore the return value. The provided property name will first be
	 * coerced to a String. If the property is a readable property of the base object, as per the
	 * JavaBeans specification, then return the result of the getter call. If the getter throws an
	 * exception, it is propagated to the caller. If the property is not found or is not readable, a
	 * PropertyNotFoundException is thrown.
	 * 
	 * @param context
	 *            The context of this evaluation.
	 * @param base
	 *            The bean to analyze.
	 * @param property
	 *            The name of the property to analyze. Will be coerced to a String.
	 * @return If the propertyResolved property of ELContext was set to true, then the value of the
	 *         given property. Otherwise, undefined.
	 * @throws NullPointerException
	 *             if context is null
	 * @throws PropertyNotFoundException
	 *             if base is not null and the specified property does not exist or is not readable.
	 * @throws ELException
	 *             if an exception was thrown while performing the property or variable resolution.
	 *             The thrown exception must be included as the cause property of this exception, if
	 *             available.
	 */
	@Override
	public Object getValue(ELContext context, Object base, Object property) {
		if (context == null) {
			throw new NullPointerException();
		}
		Object result = null;
		if (isResolvable(base)) {
			Method method = toBeanProperty(base, property).getReadMethod();
			if (method == null) {
				throw new PropertyNotFoundException("Cannot read property " + property);
			}
			try {
				result = method.invoke(base);
			} catch (InvocationTargetException e) {
				throw new ELException(e.getCause());
			} catch (Exception e) {
				throw new ELException(e);
			}
			context.setPropertyResolved(true);
		}
		return result;
	}

	/**
	 * If the base object is not null, returns whether a call to
	 * {@link #setValue(ELContext, Object, Object, Object)} will always fail. If the base is not
	 * null, the propertyResolved property of the ELContext object must be set to true by this
	 * resolver, before returning. If this property is not true after this method is called, the
	 * caller can safely assume no value was set.
	 * 
	 * @param context
	 *            The context of this evaluation.
	 * @param base
	 *            The bean to analyze.
	 * @param property
	 *            The name of the property to analyze. Will be coerced to a String.
	 * @return If the propertyResolved property of ELContext was set to true, then true if calling
	 *         the setValue method will always fail or false if it is possible that such a call may
	 *         succeed; otherwise undefined.
	 * @throws NullPointerException
	 *             if context is null
	 * @throws PropertyNotFoundException
	 *             if base is not null and the specified property does not exist or is not readable.
	 * @throws ELException
	 *             if an exception was thrown while performing the property or variable resolution.
	 *             The thrown exception must be included as the cause property of this exception, if
	 *             available.
	 */
	@Override
	public boolean isReadOnly(ELContext context, Object base, Object property) {
		if (context == null) {
			throw new NullPointerException();
		}
		boolean result = readOnly;
		if (isResolvable(base)) {
			result |= toBeanProperty(base, property).isReadOnly();
			context.setPropertyResolved(true);
		}
		return result;
	}

	/**
	 * If the base object is not null, attempts to set the value of the given property on this bean.
	 * If the base is not null, the propertyResolved property of the ELContext object must be set to
	 * true by this resolver, before returning. If this property is not true after this method is
	 * called, the caller can safely assume no value was set. If this resolver was constructed in
	 * read-only mode, this method will always throw PropertyNotWritableException. The provided
	 * property name will first be coerced to a String. If property is a writable property of base
	 * (as per the JavaBeans Specification), the setter method is called (passing value). If the
	 * property exists but does not have a setter, then a PropertyNotFoundException is thrown. If
	 * the property does not exist, a PropertyNotFoundException is thrown.
	 * 
	 * @param context
	 *            The context of this evaluation.
	 * @param base
	 *            The bean to analyze.
	 * @param property
	 *            The name of the property to analyze. Will be coerced to a String.
	 * @param value
	 *            The value to be associated with the specified key.
	 * @throws NullPointerException
	 *             if context is null
	 * @throws PropertyNotFoundException
	 *             if base is not null and the specified property does not exist or is not readable.
	 * @throws PropertyNotWritableException
	 *             if this resolver was constructed in read-only mode, or if there is no setter for
	 *             the property
	 * @throws ELException
	 *             if an exception was thrown while performing the property or variable resolution.
	 *             The thrown exception must be included as the cause property of this exception, if
	 *             available.
	 */
	@Override
	public void setValue(ELContext context, Object base, Object property, Object value) {
		if (context == null) {
			throw new NullPointerException();
		}
		if (isResolvable(base)) {
			if (readOnly) {
				throw new PropertyNotWritableException("resolver is read-only");
			}
			Method method = toBeanProperty(base, property).getWriteMethod();
			if (method == null) {
				throw new PropertyNotWritableException("Cannot write property: " + property);
			}
			try {
				method.invoke(base, value);
			} catch (InvocationTargetException e) {
				throw new ELException("Cannot write property: " + property, e.getCause());
			} catch (IllegalArgumentException e) {
				throw new ELException("Cannot write property: " + property, e);
			} catch (IllegalAccessException e) {
				throw new PropertyNotWritableException("Cannot write property: " + property, e);
			}
			context.setPropertyResolved(true);
		}
	}

	/**
	 * If the base object is not <code>null</code>, invoke the method, with the given parameters on
	 * this bean. The return value from the method is returned.
	 * 
	 * <p>
	 * If the base is not <code>null</code>, the <code>propertyResolved</code> property of the
	 * <code>ELContext</code> object must be set to <code>true</code> by this resolver, before
	 * returning. If this property is not <code>true</code> after this method is called, the caller
	 * should ignore the return value.
	 * </p>
	 * 
	 * <p>
	 * The provided method object will first be coerced to a <code>String</code>. The methods in the
	 * bean is then examined and an attempt will be made to select one for invocation. If no
	 * suitable can be found, a <code>MethodNotFoundException</code> is thrown.
	 * 
	 * If the given paramTypes is not <code>null</code>, select the method with the given name and
	 * parameter types.
	 * 
	 * Else select the method with the given name that has the same number of parameters. If there
	 * are more than one such method, the method selection process is undefined.
	 * 
	 * Else select the method with the given name that takes a variable number of arguments.
	 * 
	 * Note the resolution for overloaded methods will likely be clarified in a future version of
	 * the spec.
	 * 
	 * The provided parameters are coerced to the corresponding parameter types of the method, and
	 * the method is then invoked.
	 * 
	 * @param context
	 *            The context of this evaluation.
	 * @param base
	 *            The bean on which to invoke the method
	 * @param method
	 *            The simple name of the method to invoke. Will be coerced to a <code>String</code>.
	 *            If method is "&lt;init&gt;"or "&lt;clinit&gt;" a MethodNotFoundException is
	 *            thrown.
	 * @param paramTypes
	 *            An array of Class objects identifying the method's formal parameter types, in
	 *            declared order. Use an empty array if the method has no parameters. Can be
	 *            <code>null</code>, in which case the method's formal parameter types are assumed
	 *            to be unknown.
	 * @param params
	 *            The parameters to pass to the method, or <code>null</code> if no parameters.
	 * @return The result of the method invocation (<code>null</code> if the method has a
	 *         <code>void</code> return type).
	 * @throws MethodNotFoundException
	 *             if no suitable method can be found.
	 * @throws ELException
	 *             if an exception was thrown while performing (base, method) resolution. The thrown
	 *             exception must be included as the cause property of this exception, if available.
	 *             If the exception thrown is an <code>InvocationTargetException</code>, extract its
	 *             <code>cause</code> and pass it to the <code>ELException</code> constructor.
	 * @since 2.2
	 */
	@Override
	public Object invoke(ELContext context, Object base, Object method, Class<?>[] paramTypes, Object[] params) {
		if (context == null) {
			throw new NullPointerException();
		}
		Object result = null;
		if (isResolvable(base)) {
			if (params == null) {
				params = new Object[0];
			}
			String name = method.toString();
			ExpressionFactory factory = getExpressionFactory(context);
			Method target = findMethod(base, name, paramTypes, params, factory);
			if (target == null) {
				Class<?>[] types = new Class<?>[params.length];
				for (int i=0; i<params.length; i++) { 
					types[i] = (params[i]!=null ? params[i].getClass():null);
				} 
                throw new MethodNotFoundException(
                        String.format(
                                "Cannot find method '%s' on bean with type='%s' with parmeter types='%s'",
                                name, base.getClass(), Arrays.asList(types)
                        )
                );
			}
			try {
				result = target.invoke(base, coerceParams(getExpressionFactory(context), target, params));
			} catch (InvocationTargetException e) {
				throw new ELException(e.getCause());
			} catch (IllegalAccessException e) {
				throw new ELException(e);
			}
			context.setPropertyResolved(true);
		}
		return result;
	};

	private Method findMethod(Object base, String name, Class<?>[] types, Object[] params, ExpressionFactory factory) {
		if (types != null) {
			try {
				return findAccessibleMethod(base.getClass().getMethod(name, types));
			} catch (NoSuchMethodException e) {
				return null;
			}
		}
		try {
			return findAccessibleMethod(
				MethodDispatcher.getPublicMethod(base, name, params, factory)
			);
		}
		catch (Exception e ) {
			return null;
		}
	}

	/**
	 * Lookup an expression factory used to coerce method parameters in context under key
	 * <code>"javax.el.ExpressionFactory"</code>.
	 * If no expression factory can be found under that key, use a default instance created with
	 * {@link ExpressionFactory#newInstance()}.
	 * @param context
	 *            The context of this evaluation.
	 * @return expression factory instance
	 */
	private ExpressionFactory getExpressionFactory(ELContext context) {
		Object obj = context.getContext(ExpressionFactory.class);
		if (obj instanceof ExpressionFactory) {
			return (ExpressionFactory)obj;
		}
		if (defaultFactory == null) {
			defaultFactory = ExpressionFactory.newInstance();
		}
		return defaultFactory;
	}
	
	private Object[] coerceParams(ExpressionFactory factory, Method method, Object[] params) {
		Class<?>[] types = method.getParameterTypes();
		Object[] args = new Object[types.length];
		
		// fixed arity methods
		if (!method.isVarArgs()) {
			for (int i = 0; i < args.length; i++) {
				Array.set(args, i, coerceValue(factory, params[i], types[i]));
			}
		}
		// varArgs methods
		else {	
			int varArgIdx = types.length - 1;
			
			// handle all but the varArg parameters
			for (int i = 0; i < varArgIdx; i++) {
				Array.set(args, i, coerceValue(factory, params[i], types[i]));
			}

			Class<?> varargType = types[varArgIdx].getComponentType();
			// if the param does not specify vararg
			if (varArgIdx == params.length) {
				args[varArgIdx] = Array.newInstance(varargType, 0);
			}
			// handle the varArg parameter
			else if (params[varArgIdx] != null && !params[varArgIdx].getClass().isArray()) {
				int length = params.length - varArgIdx;
				args[varArgIdx] = Array.newInstance(varargType, length);
				for (int i = 0; i < length; i++) {
					Array.set(args[varArgIdx], i, 
						coerceValue(factory, params[varArgIdx + i], varargType)
					);
				}
			}
			// we got the varArgs parameter as an array
			else { 
				Array.set(args, varArgIdx, 
					coerceValue(factory, params[varArgIdx], types[varArgIdx])
				);
			} 
		} 
		return args;
	}
	
	private Object coerceValue(ExpressionFactory factory, Object value, Class<?> type) {
		if (type.isPrimitive() || (!type.isArray() && value != null)) {
			value = factory.coerceToType(value, type);
		}
		else if (value != null && value.getClass().isArray() ) {
			int len = Array.getLength(value);
			Class<?> arrayType = type.getComponentType();
			
			Object oValue = value;
			if (!type.isInstance(value)) { // use source array as is
				value = Array.newInstance(arrayType, len);
			}
			for ( int i=0; i < len; i++ ) {
				Array.set(value, i, 
					coerceValue(factory, Array.get(oValue, i), arrayType)
				);
			}
			
		}
		return value;
	}
	
	/**
	 * Test whether the given base should be resolved by this ELResolver.
	 * 
	 * @param base
	 *            The bean to analyze.
	 * @param property
	 *            The name of the property to analyze. Will be coerced to a String.
	 * @return base != null
	 */
	private final boolean isResolvable(Object base) {
		return base != null;
	}

	/**
	 * Lookup BeanProperty for the given (base, property) pair.
	 * 
	 * @param base
	 *            The bean to analyze.
	 * @param property
	 *            The name of the property to analyze. Will be coerced to a String.
	 * @return The BeanProperty representing (base, property).
	 * @throws PropertyNotFoundException
	 *             if no BeanProperty can be found.
	 */
	private final BeanProperty toBeanProperty(Object base, Object property) {
		BeanProperties beanProperties = cache.get(base.getClass());
		if (beanProperties == null) {
			BeanProperties newBeanProperties = new BeanProperties(base.getClass());
			beanProperties = cache.putIfAbsent(base.getClass(), newBeanProperties);
			if (beanProperties == null) { // put succeeded, use new value
				beanProperties = newBeanProperties;
			}
		}
		BeanProperty beanProperty = property == null ? null : beanProperties.getBeanProperty(property.toString());
		if (beanProperty == null) {
			throw new PropertyNotFoundException("Could not find property " + property + " in " + base.getClass());
		}
		return beanProperty;
	}

	/**
	 * This method is not part of the API, though it can be used (reflectively) by clients of this
	 * class to remove entries from the cache when the beans are being unloaded.
	 * 
	 * Note: this method is present in the reference implementation, so we're adding it here to ease
	 * migration.
	 * 
	 * @param classloader
	 *            The classLoader used to load the beans.
	 */
	@SuppressWarnings("unused")
	private final void purgeBeanClasses(ClassLoader loader) {
		Iterator<Class<?>> classes = cache.keySet().iterator();
		while (classes.hasNext()) {
			if (loader == classes.next().getClassLoader()) {
				classes.remove();
			}
		}
	}
	
	/**
	 * Dynamically dispatches methods calls.
	 * 
	 * Support dispatching of overloaded methods and vararg methods. Once a method
	 * has been resolved the result will be cashed to speed up further method invocations
	 * to the same method.
	 */
	static class MethodDispatcher {
		static final private Map<Class<?>, Class<?>> autoBoxingMap = new HashMap<Class<?>, Class<?>>();
		static {
			autoBoxingMap.put(Short.TYPE, Short.class);
			autoBoxingMap.put(Integer.TYPE, Integer.class);
			autoBoxingMap.put(Long.TYPE, Long.class);
			autoBoxingMap.put(Float.TYPE, Float.class);
			autoBoxingMap.put(Double.TYPE, Double.class);
			autoBoxingMap.put(Character.TYPE, Character.class);
			autoBoxingMap.put(Byte.TYPE, Byte.class);
			autoBoxingMap.put(Boolean.TYPE, Boolean.class);
		}
		static final private Map<Class<?>, Class<?>> autoUnboxingMap = new HashMap<Class<?>, Class<?>>();
		static {
			autoUnboxingMap.put(Short.class, Short.TYPE);
			autoUnboxingMap.put(Integer.class, Integer.TYPE);
			autoUnboxingMap.put(Long.class, Long.TYPE);
			autoUnboxingMap.put(Float.class, Float.TYPE);
			autoUnboxingMap.put(Double.class, Double.TYPE);
			autoUnboxingMap.put(Character.class, Character.TYPE);
			autoUnboxingMap.put(Byte.class, Byte.TYPE);
			autoUnboxingMap.put(Boolean.class, Boolean.TYPE);
		}

		private static final ConcurrentHashMap<List<Object>,Method> methodCache = 
				new ConcurrentHashMap<List<Object>, Method>();
		
		/**
		 * Predicate decides whether parameter can be converted into a given type.
		 */
		static interface Predicate<P> {
			/**
			 * Checks whether the given parameter be converted into the specified class.
			 * 
			 * @param clazz Class into which the given parameter should be converted to
			 * @param param Parameter which has to be converted
			 * @return true if conversion is possible false otherwise
			 */
			public boolean match(Class<?> clazz, P param);
		}

		/**
		 * A MethodFilter verifies whether a list of parameters are compatible with
		 * the formal parameters of a given method. Depending on whether it is a
		 * fixed varity method or a variable varity method the way comparison is 
		 * done varies.
		 */
		static interface MethodFilter {
			/**
			 * Checks whether a method can be invoked with the given paerameters.
			 * 
			 * @param m Method to be tested with the given parameters
			 * @param param Parameters to be checked against the given method
			 * @param predicat The predicate used to check individual parameter
			 * @return true if the method can be invoked with the given parameters false otherwise
			 */
			<P> boolean match(Method m, P[] param, Predicate<P> predicat);
		}
		
		/**
		 * Create a key used for the method cache.
		 * 
		 * Note: we use a list 
		 * @param oType class one witch the method is defined
		 * @param name Name of the method
		 * @param pTypes Parameter types used to resolve the method
		 * @param factory
		 * @return immutable list used as key for the method cache
		 */
		private static List<Object> createCacheKey(
			Class<?> oType, String name, Class<?>[] pTypes, ExpressionFactory factory
		) {
			ArrayList<Object> result = new ArrayList<Object>();
			result.add(oType);
			result.add(name);
			for (Class<?> c : pTypes) {
				result.add(c);
			}
			result.add(factory.getClass());
			return Collections.unmodifiableList(result); // collection keys should be immutable
		}
		
		/**
		 * Resolve the public method matching the method name and given parameters.
		 * 
		 * @param obj The object on which the method gets invoked
		 * @param name The name of the method
		 * @param params The actual parameters passed to the method invocation
		 * @param factory 
		 * @return Method object if a method could be found on the given object matching the 
		 * parameters <code>null</code> otherwise.
		 */
		public static Method getPublicMethod(
				Object obj, String name, Object[] params,
				ExpressionFactory factory
		) {
			Class<?> oType = obj.getClass();
			Class<?>[] pTypes = deriveTypes(params);
			
			// lookup method in cache
			List<Object> key = createCacheKey(oType, name, pTypes, factory);
			Method method = methodCache.get(key);
			if ( method != null ) {
				return method;
			}
			
			/* use JDK to find the method
			 * NOTE: We will not find the method if
			 * a) one of the parameters is <null>
			 * b) parsing  from string does not result in the right type
			 *    (e.g. 1 will be parsed as Long - just pondering why)
			 */
			try {
				return oType.getMethod(name, pTypes);
			} catch (NoSuchMethodException e) { } // no method found (swallow)


			/* Phase 0: 
			 * Identify Potentially Applicable Methods
			 */
			ArrayList<Method> fixArity = new ArrayList<Method>();
			ArrayList<Method> varArgs = new ArrayList<Method>();
			identifyCandidates(oType, name, params.length, fixArity, varArgs);
			
			List<Method> matched = null;
			Predicate<Object> juelCoerce = new JuelCoercePredicat(factory);

			/* Phase 1: 
			 * Identify Matching Arity Methods Applicable by Subtyping
			 */
			if ( fixArity.size() != 0 ) {
				matched = filterMethods(fixArity, pTypes, 
					MethodFilters.FIXED_ARITY, Predicates.SUB_TYPE
				);
				if ( matched.size() != 0 ) {
					return selectMostSpecific(
						matched, MethodComparators.FIXED_ARITY, key
					);
				}
	
				/* Phase 2: 
				 * Identify Matching Arity Methods Applicable by Method Invocation Conversion
				 */
				matched = filterMethods(fixArity, pTypes, 
					MethodFilters.FIXED_ARITY, Predicates.CONVERSION
				);
				if ( matched.size() != 0 ) {
					return selectMostSpecific(
						matched, MethodComparators.FIXED_ARITY, key
					);
				}
	
				/* Phase 2*: 
				 * Identify Matching Arity Methods Applicable by Method Invocation Conversion 
				 * (non JVM just for JUEL)
				 */
				matched = filterMethods(fixArity, params, 
					MethodFilters.FIXED_ARITY, juelCoerce
				);
				if ( matched.size() != 0 ) {
					return selectMostSpecific(
						matched, MethodComparators.FIXED_ARITY, key
					);
				}
			}

			/* Phase 3: 
			 * Identify Applicable Variable Arity Methods  
			 */
			if( varArgs.size() != 0 ) {
				matched = filterMethods(varArgs, pTypes, 
					MethodFilters.VARARG_ARITY_TYPE, Predicates.CONVERSION
				);
				if ( matched.size() != 0 ) {
					return selectMostSpecific(
						matched, MethodComparators.VARARG_ARITIY, key
					);
				}
	
				/* Phase 3*: 
				 * Identify Applicable Variable Arity Methods  
				 * (non JVM just for JUEL)
				 */
				matched = filterMethods(varArgs, params, 
						MethodFilters.VARARG_ARITY_PARAM, juelCoerce
					);
				if ( matched.size() != 0 ) {
					return selectMostSpecific(
						matched, MethodComparators.VARARG_ARITIY, key
					);
				}
			}
			
			return null; // no matching method could be found
		}
		
		/**
		 * Returns an array containing the types (Class) of the passed object.
		 * 
		 * If a given object (params[i]) should be <code>null</code> the
		 * corresponding type (Class) will be null as well! 
		 */
		private static Class<?>[] deriveTypes(Object[] params) {
			Class<?>[] types = new Class<?>[params.length];
			for (int i=0; i<params.length; i++) {
				types[i] = ((params[i] != null) ? params[i].getClass() : null);
			}
			return types;
		}

		/** 
		 * Identify Potentially Applicable Methods
		 * 
		 * a) The method name matches
		 * b) The method is accessible (given as we only look at public methods)
		 * c) For fixed arity methods the number of arguments and parameters must be equal
		 * d) for variable arity methods the number arguments must be >= to the 
		 *    number of parameters-1
		 *    
		 * @param clazz The class for which all public methods are inspected
		 * @param methodName The name of the method which we are looking for
		 * @param actualParamCount The number of arguments we what to pass to the method
		 * @param fixArity List where all matching fixed arity methods are stored 
		 * @param varArgs List where all matching variable arity methods are stored 
		 */
		private static void identifyCandidates(
			final Class<?> clazz,
			final String methodName, 
			final int actualParamCount,
			List<Method> fixArity, 
			List<Method> varArgs
		) {
	
			for (Method method : clazz.getMethods()) {
				if (method.getName().equals(methodName)) {
					int formalParamCount = method.getParameterTypes().length;
					if (method.isVarArgs() && actualParamCount >= formalParamCount - 1) {
						if ( method != null ) {
							varArgs.add(method);
						}
					} 
					else if (actualParamCount == formalParamCount) {
						if ( method != null ) {
							fixArity.add(method);
						}
					}
				}
			}
		}

		/**
		 *  Filters the array of methods passed using the given Filterr
		 *  
		 * @param methods List of method to be filtered
		 * @param params Actual parameters used (used as parameters to the filter)
		 * @param filter The filter to  be  applied
		 * @param predicat Predicate used by the filter
		 * @return Methods which passed the filter
		 */
		private static <T> List<Method> filterMethods(
			List<Method> methods, T[] params, 
			MethodFilter filter, Predicate<T> predicat
		) {
			List<Method> result = new ArrayList<Method>();
			for (Method method : methods) {
				if (filter.match(method, params, predicat)) {
					result.add(method);
				}
			}
			return result;
		}
		
		/**
		 * Select the most specific method from the given list.
		 * 
		 * How the most specific method is defined is specified in the JVM 
		 * specification chapter '15.12.2.5 Choosing the Most Specific Method'.
		 * Informally one can say a method 1 is more specific then a method 2 if 
		 * any call (combination of parameters) that can be handled by method 1 
		 * also can handled by method 2. Thus method 1 can be removed from the 
		 * source and the code still compiles. But if method 2 is removed then the
		 * code would not compile anymore.
		 *  
		 * @param methods List of method from which the most specific is chosen
		 * @param comparator Comparator used to compare tow methods based on how 
		 * specific they are
		 * @param key Key under which a method is added to the cache in case a
		 * most specific method was found. 
		 * @return The most specific method if there is one.
		 */
		private static Method selectMostSpecific(
			List<Method> methods, Comparator<Method> comparator,
			List<Object> key
		) {
			Method method = null;
			switch (methods.size()) {
			case 0:
				break;
			case 1:
				method = methods.get(0);
				break;
			case 2:
				int res = comparator.compare(methods.get(0), methods.get(1));
				if ( res < 0 ) {
					method = methods.get(0);
				}
				else if ( res > 0 ) {
					method = methods.get(1);
				}
				break;
			default:
				Collections.sort(methods, comparator);
				if (comparator.compare(methods.get(0), methods.get(1)) != 0) {
					method = methods.get(0);
				}
			}
			if ( method != null ) {
				methodCache.put(key, method);
			}
			return method;
		}
		
		public static class MethodFilters {
			public static final MethodFilter FIXED_ARITY = new FixedArity();
			public static final MethodFilter VARARG_ARITY_TYPE = new VarArgArityType();
			public static final MethodFilter VARARG_ARITY_PARAM = new VarArgArityParam();
			
			/**
			 * Compares a list of parameters against a fixed vaity method. This is done
			 * by simple testing the actual parameter <code>i</code> against the 
			 * formal parameter <code>i</code>.
			 */
			private static class FixedArity implements MethodFilter {
				@Override
				public <T> boolean match(Method method, T[] params, Predicate<T> predicat) {
					Class<?>[] formalTypes = method.getParameterTypes();
					for (int i = 0; i < formalTypes.length; i++) {
						if(!predicat.match(formalTypes[i], params[i])) {
							return false;
						}
					}
					return true;
				}
			}

			/**
			 * Compares a list of parameters against a variable vaity method. This is done
			 * by simple testing the actual parameter <code>i</code> against the 
			 * formal parameter <code>i</code> up the the <code>n-1</code>. The last
			 * formal parameter must then match all the remaining actual parmameters.
			 */
			private abstract static class VarArgArity implements MethodFilter{
				
				protected abstract boolean isArray(Object o);
				
				@Override
				public <T> boolean match(Method method, T[] params, Predicate<T> predicat) {
					Class<?>[] types = method.getParameterTypes();
					int varArgIdx = types.length-1;
					// test all but the vararg parameters
					for (int i = 0; i < varArgIdx; i++) {
						if(!predicat.match(types[i], params[i])) {
							return false;
						}
					}
					// if the param does not specify any vararg
					if (params.length == varArgIdx) {
						return true;
					}
					// test the varArg parameter
					if (!isArray(params[varArgIdx]) ) {
						Class<?> argVarType = types[varArgIdx].getComponentType();
						for (int i = varArgIdx; i < params.length; i++) {
							if (!predicat.match(argVarType, params[i])) {
								return false;
							}
						}	
					}
					else if ( !predicat.match(types[varArgIdx], params[varArgIdx]) ) {
						return false;
					}
					return true;
				}
			}
		}
		private static class VarArgArityType extends MethodFilters.VarArgArity {

			@Override
			protected boolean isArray(Object o) {
				return ((o!=null)?((Class<?>)o).isArray():false);
			}
		}
		private static class VarArgArityParam extends MethodFilters.VarArgArity {

			@Override
			protected boolean isArray(Object o) {
				return ((o!=null)?o.getClass().isArray():false);
			}
		}

		
		public static class Predicates {
			public static final Predicate<Class<?>> SUB_TYPE = new SubType();
			public static final Predicate<Class<?>> CONVERSION = new Conversion();
			
			/**
			 * Tests whether the a class (actual parameter) is a sub-type of the formal
			 * parameter. See JVM specification '4.10 Subtyping' for details.
			 */
			private static class SubType implements Predicate<Class<?>> {
				static final private Set<List<Class<?>>> pIsSuperType = new HashSet<List<Class<?>>>();
				static final private List<Class<?>> tuple(Class<?> c1, Class<?> c2) {
					List<Class<?>> t = new ArrayList<Class<?>>();
					t.add(c1); t.add(c2);
					return t;
				}
				static {
					final Map<Class<?>, Class<?>> pSuperTypes = new HashMap<Class<?>, Class<?>>();
					// sub-type definition for primitive types as defined by the JVM spec
					pSuperTypes.put(Byte.TYPE, Byte.TYPE);
					pSuperTypes.put(Short.TYPE, Byte.TYPE);
					pSuperTypes.put(Integer.TYPE, Short.TYPE);
					pSuperTypes.put(Long.TYPE, Integer.TYPE);
					pSuperTypes.put(Float.TYPE, Long.TYPE);
					pSuperTypes.put(Double.TYPE, Float.TYPE);
					// build transitive closure. at runtime the test will the be a simple map-lookup
					for (Class<?> superClass : pSuperTypes.keySet()) {
						Class<?> subClass = pSuperTypes.get(superClass);
						
						Class<?> oldSubClass = null;
						do {
							oldSubClass = subClass;
							pIsSuperType.add(tuple(superClass, subClass));
							subClass = pSuperTypes.get(subClass);
							
						} while ( oldSubClass != subClass );
					}
				}
				
				public boolean match(Class<?> formalType, Class<?> actualType) {
					// null matches anything
					if ( actualType == null ) {
						return true;
					}
					// formal parameter is a super type (non primitive) 
					if (formalType.isAssignableFrom(actualType)) {
						return true; 
					}
					// formal parameter is a super type (primitive) 
					if ( pIsSuperType.contains(tuple(formalType, actualType))) {
						return true; 
					}
					return false;
				}
			}
			
			/**
			 * Tests whether the a type (actual parameter) can be converted to another
			 * type (formal parameter) by applying either
			 * <li>auto boxing and an optional widening reference conversion</li>
			 * <li>un-boxing and an optional widening primitive conversion</li>    
			 */
			private static class Conversion extends SubType {
				
				public boolean match(Class<?> formalType, Class<?> actualType) {
					// do optional boxing or un-boxing
					
					// null can be anything
					if ( actualType == null ) { 
						return true;
					}
					if ( ! actualType.isPrimitive() && formalType.isPrimitive() ) {
						actualType = autoUnboxingMap.get(actualType);
						if ( actualType == null ) { // unboxing failed
							return false;
						}
					}
					else if ( actualType.isPrimitive() && ! formalType.isPrimitive() ) {
						actualType = autoBoxingMap.get(actualType);
					}
					return super.match(formalType, actualType);	 
				}
			}
			
			
			/**
			 * Tests whether the a type (actual parameter) can be converted to another
			 * type (formal parameter) by applying JUELS type coerce implementation. 
			 * 
			 * Note: This is not part of the JVM specification and violates type savety 
			 */
			static class JuelCoercePredicat implements Predicate<Object> {
				private ExpressionFactory factory;
				JuelCoercePredicat(ExpressionFactory factory) {
					this.factory = factory;
				}
				public boolean match(Class<?> formalType, Object actualParam) {
					if (actualParam != null) { // null matches anything
						try {
							// if both parameters are arrays lets test the
							// actual parameters elements against the formal
							// parameters component type. 
							if (actualParam != null &&
								formalType.isArray() && 
								actualParam.getClass().isArray() 
							) {
								formalType = formalType.getComponentType();
								int len = Array.getLength(actualParam);
								for(int i=0; i<len; i++) {
									Object param = Array.get(actualParam, i);
									if (!match(formalType, param)) {
										return false;
									}
								}
							}
							else {
								factory.coerceToType(actualParam, formalType);
							}
						} catch (Exception e) {
							return false; }
					}
					return true;
				}
			}
		}
		
		public static class MethodComparators {
			public static final Comparator<Method> FIXED_ARITY = new FixedArity();
			public static final Comparator<Method> VARARG_ARITIY = new VarArgArity();
			
			static boolean isAssignable(Class<?> c1, Class<?> c2) {
				return Predicates.CONVERSION.match(c1, c2);
			}

			/**
			 * Compares to fixed arity methods based on how specific they are.
			 * 
			 * See JVM specification chapter 
			 * '15.12.2.5 Choosing the Most Specific Method'
			 */
			private static class FixedArity implements Comparator<Method> 	{
				public int compare(Method m1, Method m2) {
					Class<?>[] p1 = m1.getParameterTypes();
					Class<?>[] p2 = m2.getParameterTypes();
					boolean s1 = true;
					boolean s2 = true;
					for (int i = 0; i < p1.length; i++) {
						s1 &= isAssignable(p1[i], p2[i]);
						s2 &= isAssignable(p2[i], p1[i]);
					}
					if (s1 && !s2) {
						return 1;
					}
					if (!s1 && s2) {
						return -1;
					}
					return 0;
				}
			}
			
			/**
			 * Compares to variable arity methods based on how specific they are.
			 * 
			 * See JVM specification chapter 
			 * '15.12.2.5 Choosing the Most Specific Method'
			 */
			private static class VarArgArity implements Comparator<Method> 	{
				public int compare(Method m1, Method m2) {
					
					Class<?>[] p1 = m1.getParameterTypes();
					Class<?>[] p2 = m2.getParameterTypes();
					int l1 = p1.length;
					int l2 = p2.length;
					
					if ( l1 > l2 ) {
						return - compare(p2, l2, p1, l1);
					}
					else {
						return compare(p1, l1, p2, l2);
					}
				}
				
				private int compare(Class<?>[] p1, int l1, Class<?>[] p2, int l2 ) { 
					boolean s1 = true;
					boolean s2 = true;
					
					int len = Math.min(l1, l2)-1;
					for (int i = 0; i < len; i++) {
						s1 &= isAssignable(p1[i], p2[i]);
						s2 &= isAssignable(p2[i], p1[i]);
					}
					
					int last = l2-1;
					for (int i = len; i < last; i++) {
						Class<?> c1 = p1[len].getComponentType();
						Class<?> c2 = p2[i];
						s1 &= isAssignable(c1, c2);
						s2 &= isAssignable(c2, c1);
					}
					
					Class<?> c1 = p1[l1 - 1].getComponentType();
					Class<?> c2 = p2[last].getComponentType();
					// Autoboxing the array component types
					if (c1.isPrimitive()) {
						c1 = autoBoxingMap.get(c1);
					}
					if (c2.isPrimitive()) {
						c2 = autoBoxingMap.get(c2);
					}
					// compare the array component type
					s1 &= isAssignable(c1, c2);
					s2 &= isAssignable(c2, c1);

					if (s1 && !s2) {
						return 1;
					}
					if (!s1 && s2) {
						return -1;
					}
					return 0;
				}
			}
		}
	}
	
}
