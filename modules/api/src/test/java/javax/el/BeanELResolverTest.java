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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.el.test.TestClass;

import junit.framework.TestCase;

public class BeanELResolverTest extends TestCase {
	public static class TestBean {
		int readOnly = 123;
		int readWrite = 456;
		int writeOnly = 789;
		public int getReadOnly() {
			return readOnly;
		}
		protected void setReadOnly(int readOnly) {
			this.readOnly = readOnly;
		}
		public int getReadWrite() {
			return readWrite;
		}
		public void setReadWrite(int readWrite) {
			this.readWrite = readWrite;
		}
		int getWriteOnly() {
			return writeOnly;
		}
		public void setWriteOnly(int writeOnly) {
			this.writeOnly = writeOnly;
		}
		public int add(int n, int... rest) {
			for (int x : rest) {
				n += x;
			}
			return n;
		}
		public String cat(String... strings) {
			StringBuilder b = new StringBuilder();
			for (String s : strings) {
				b.append(s);
			}
			return b.toString();
		}
		int secret() {
			return 42;
		}
	}

	ELContext context = new TestContext();

	public void testGetCommonPropertyType() {
		BeanELResolver resolver = new BeanELResolver();

		// base is bean --> Object.class
		assertSame(Object.class, resolver.getCommonPropertyType(context, new TestBean()));

		// base == null --> null
		assertNull(resolver.getCommonPropertyType(context, null));
	}

	public void testGetFeatureDescriptors() {
		BeanELResolver resolver = new BeanELResolver();

		// base == null --> null
		assertNull(resolver.getCommonPropertyType(context, null));

		// base is bean --> features...
		Iterator<FeatureDescriptor> iterator = resolver.getFeatureDescriptors(context, new TestBean());
		List<String> names = new ArrayList<String>();
		while (iterator.hasNext()) {
			FeatureDescriptor feature = iterator.next();
			names.add(feature.getName());
			Class<?> type = "class".equals(feature.getName()) ? Class.class : int.class;
			assertSame(type, feature.getValue(ELResolver.TYPE));
			assertSame(Boolean.TRUE, feature.getValue(ELResolver.RESOLVABLE_AT_DESIGN_TIME));
		}
		assertTrue(names.contains("class"));
		assertTrue(names.contains("readOnly"));
		assertTrue(names.contains("readWrite"));
		assertTrue(names.contains("writeOnly"));
		assertEquals(4, names.size());		
	}

	public void testGetType() {
		BeanELResolver resolver = new BeanELResolver();

		// base == null --> null
		context.setPropertyResolved(false);
		assertNull(resolver.getType(context, null, "foo"));
		assertFalse(context.isPropertyResolved());

		// base is bean, property == "readWrite" --> int.class
		context.setPropertyResolved(false);
		assertSame(int.class, resolver.getType(context, new TestBean(), "readWrite"));
		assertTrue(context.isPropertyResolved());

		// base is bean, property == null --> exception
		try {
			resolver.getType(context, new TestBean(), null);
			fail();
		} catch (PropertyNotFoundException e) {
			// fine
		}

		// base is bean, property != null, but doesn't exist --> exception
		try {
			resolver.getType(context, new TestBean(), "doesntExist");
			fail();
		} catch (PropertyNotFoundException e) {
			// fine
		}
	}

	public void testGetValue() {
		Properties properties = new Properties();
		properties.setProperty(ExpressionFactory.class.getName(), TestFactory.class.getName());
		BeanELResolver resolver = new BeanELResolver();

		// base == null --> null
		context.setPropertyResolved(false);
		assertNull(resolver.getValue(context, null, "foo"));
		assertFalse(context.isPropertyResolved());

		// base is bean, property == "readWrite" --> 123
		context.setPropertyResolved(false);
		assertEquals(456, resolver.getValue(context, new TestBean(), "readWrite"));
		assertTrue(context.isPropertyResolved());

		// base is bean, property == "writeOnly" --> exception
		try {
			resolver.getValue(context, new TestBean(), "writeOnly");
			fail();
		} catch (PropertyNotFoundException e) {
			// fine
		}

		// base is bean, property != null, but doesn't exist --> exception
		try {
			resolver.getValue(context, new TestBean(), "doesntExist");
			fail();
		} catch (PropertyNotFoundException e) {
			// fine
		}
	}

	public void testGetValue2() {
		Properties properties = new Properties();
		properties.setProperty(ExpressionFactory.class.getName(), TestFactory.class.getName());
		BeanELResolver resolver = new BeanELResolver();

		context.setPropertyResolved(false);
		assertEquals(42, resolver.getValue(context, new TestClass().getAnonymousTestInterface(), "fourtyTwo"));
		assertTrue(context.isPropertyResolved());

		context.setPropertyResolved(false);
		assertEquals(42, resolver.getValue(context, new TestClass().getNestedTestInterface(), "fourtyTwo"));
		assertTrue(context.isPropertyResolved());

		context.setPropertyResolved(false);
		assertEquals(42, resolver.getValue(context, new TestClass().getNestedTestInterface2(), "fourtyTwo"));
		assertTrue(context.isPropertyResolved());
	}

	public void testIsReadOnly() {
		BeanELResolver resolver = new BeanELResolver();
		BeanELResolver resolverReadOnly = new BeanELResolver(true);

		// base == null --> false
		context.setPropertyResolved(false);
		assertFalse(resolver.isReadOnly(context, null, "foo"));
		assertFalse(context.isPropertyResolved());

		// base is bean, property == "readOnly" --> true
		context.setPropertyResolved(false);
		assertTrue(resolver.isReadOnly(context, new TestBean(), "readOnly"));
		assertTrue(context.isPropertyResolved());

		// base is bean, property == "readWrite" --> false
		context.setPropertyResolved(false);
		assertFalse(resolver.isReadOnly(context, new TestBean(), "readWrite"));
		assertTrue(context.isPropertyResolved());

		// base is bean, property == "writeOnly" --> false
		context.setPropertyResolved(false);
		assertFalse(resolver.isReadOnly(context, new TestBean(), "writeOnly"));
		assertTrue(context.isPropertyResolved());

		// base is bean, property == 1 --> true (use read-only resolver)
		context.setPropertyResolved(false);
		assertTrue(resolverReadOnly.isReadOnly(context, new TestBean(), "readWrite"));
		assertTrue(context.isPropertyResolved());

		// is bean, property != null, but doesn't exist --> exception
		try {
			resolver.isReadOnly(context, new TestBean(), "doesntExist");
			fail();
		} catch (PropertyNotFoundException e) {
			// fine
		}
	}

	public void testSetValue() {
		BeanELResolver resolver = new BeanELResolver();
		BeanELResolver resolverReadOnly = new BeanELResolver(true);

		// base == null --> unresolved
		context.setPropertyResolved(false);
		resolver.setValue(context, null, "foo", -1);
		assertFalse(context.isPropertyResolved());

		// base is bean, property == "readWrite" --> ok
		context.setPropertyResolved(false);
		TestBean bean = new TestBean();
		resolver.setValue(context, bean, "readWrite", 999);
		assertEquals(999, bean.getReadWrite());
		assertTrue(context.isPropertyResolved());

		// base is bean, property == "readOnly" --> exception
		try {
			resolver.setValue(context, new TestBean(), "readOnly", 1);
			fail();
		} catch (PropertyNotWritableException e) {
			// fine
		}

		// base is bean, property != null, but doesn't exist --> exception
		try {
			resolver.setValue(context, new TestBean(), "doesntExist", 1);
			fail();
		} catch (PropertyNotFoundException e) {
			// fine
		}

		// base is bean, property == "readWrite", invalid value --> exception
		try {
			resolver.setValue(context, new TestBean(), "readWrite", "invalid");
			fail();
		} catch (ELException e) {
			// fine, according to the spec...
		} catch (IllegalArgumentException e) {
			// violates the spec, but we'll accept this...
		}

		// read-only resolver
		try {
			resolverReadOnly.setValue(context, bean, "readWrite", 999);
			fail();
		} catch (PropertyNotWritableException e) {
			// fine
		}
	}

	public void testInvoke() {
		BeanELResolver resolver = new BeanELResolver();
		
		assertEquals(1, resolver.invoke(context, new TestBean(), "add", null, new Integer[]{1}));
		assertEquals(6, resolver.invoke(context, new TestBean(), "add", null, new Integer[]{1, 2, 3}));
		assertEquals(6, resolver.invoke(context, new TestBean(), "add", null, new String[]{"1", "2", "3"}));
		assertEquals(6, resolver.invoke(context, new TestBean(), "add", null, new Object[]{1, new int[]{2, 3}}));
		assertEquals(6, resolver.invoke(context, new TestBean(), "add", null, new Object[]{1, new Double[]{2.0, 3.0}}));

		assertEquals("", resolver.invoke(context, new TestBean(), "cat", null, new Object[0]));
		assertEquals("", resolver.invoke(context, new TestBean(), "cat", null, null));
		assertEquals("123", resolver.invoke(context, new TestBean(), "cat", null, new Object[]{123}));
		assertEquals("123", resolver.invoke(context, new TestBean(), "cat", null, new Integer[]{1, 2, 3}));
		assertEquals("123", resolver.invoke(context, new TestBean(), "cat", null, new Object[]{new String[]{"1", "2", "3"}}));
	
		TestBean bean = new TestBean();
		bean.setReadWrite(1);
		assertNull(resolver.invoke(context, bean, "setReadWrite", null, new Object[]{null}));
		assertEquals(0, bean.getReadWrite());
		assertNull(resolver.invoke(context, bean, "setReadWrite", null, new Object[]{5}));
		assertEquals(5, bean.getReadWrite());
		try {
			resolver.invoke(context, new TestBean(), "secret", null, null);
			fail();
		} catch (MethodNotFoundException e) {
			// fine
		}
	}
	
	public void testGetMethodUsingPhase1() {
		@SuppressWarnings("unused")
		class TestGetMethodUsingPhase1Class {
			public String m(Integer i) {
				return "TestGetMethodUsingPhase1:m(Integer " + i + ")";
			}

			public String m(Object o, Number n) {
				return String.format("TestGetMethodUsingPhase1.m(Object %s, Number %s)", o, n);
			}

			public String m(Object o, Object n) {
				return String.format("TestGetMethodUsingPhase1.m(Object %s, Number %s)", o, n);
			}

			public String m(Object o, long l) {
				return String.format("TestGetMethodUsingPhase1.m(Object %s, long %s)", o, l);
			}

			public String m(Object o, Number[] nums) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase1:m");
				sb.append("Object ").append(o);
				for (Number n: nums) {
					sb.append(n).append(";");
				}
				return sb.toString();
			}
		}

		BeanELResolver resolver = new BeanELResolver();
		TestGetMethodUsingPhase1Class bean = new TestGetMethodUsingPhase1Class();
		Integer i = Integer.valueOf(3);
		Integer[] i_arr = new Integer[] {1, 2, 3};

		// invoke:		m(null)
		// select:		m(Integer)
		Object e = bean.m(null); 
		Object a  = resolver.invoke(context, bean, "m", null, new Object[]{null}); 
		assertEquals(e, a);

		// invoke:		m(null, Integer)
		// select:		m(Object, Number)	-- more specific
		//				m(Object, Object)
		e = bean.m(null, i); 
		a  = resolver.invoke(context, bean, "m", null, new Object[]{null, i}); 
		assertEquals(e, a);

		// invoke:		m(null, Integer[])
		// select:		m(Object, Number[])
		e = bean.m(null, i_arr);
		a  = resolver.invoke(context, bean, "m", null, new Object[]{null, i_arr}); 
		assertEquals(e, a);
	}

	public void testGetMethodFail() {
		@SuppressWarnings("unused")
		class TestFailToResolveClass {
			public void m(Integer[] nums) {System.err.print("TestFailToResolveClass.Integer[] nums");}

			public void m2(Integer i) {System.err.print("TestFailToResolveClass.m2(Integer i)");}
			public void m2(String i) {System.err.print("TestFailToResolveClass.m2(String i)");}

			public void m3(Integer ... s) {System.err.print("TestFailToResolveClass.m3(Integer ... s)");}

			public void m4(long ... l) {System.err.println("TestFailToResolveClass.m4(long ... l)");}
			public void m4(int ... i) {System.err.println("TestFailToResolveClass.m4(int ... i)");}

			public void m5(String s1, String ... s2) {System.err.print("TestFailToResolveClass.m5(String s1, String ... s2)");}
			public void m5(String ... s) {System.err.print("TestFailToResolveClass.m5(String ... s)");}
			
			public void m6(Integer[][] i) {System.err.print("TestFailToResolveClass.m6(Integer[][] i)");}
		}

		BeanELResolver resolver = new BeanELResolver();
		TestFailToResolveClass bean = new TestFailToResolveClass();

		int[] int_arr = new int[] {2, 3, 4};
		Long l = Long.valueOf(2);
		Integer i = Integer.valueOf(3);
		Object[] obj_arr = new Object[] {Integer.valueOf(1), "abc"};

		// can't use Object[] for Integer[]
		try {
			//bean.m(obj_arr);
			resolver.invoke(context, bean, "m", null, new Object[] {obj_arr}); 
			assertTrue(false);
		}
		catch (Exception e) {assertTrue(e instanceof MethodNotFoundException);}

		// can't determine which of m2 is more specific
		try {
			//bean.m(l);
			resolver.invoke(context, bean, "m", null, new Object[] {l}); 
			assertTrue(false);
		}
		catch (Exception e) {assertTrue(e instanceof MethodNotFoundException);}

		// wrong method name
		try {
			//m.noSuchMethod();
			resolver.invoke(context, bean, "noSuchMethod", null, null); 
			assertTrue(false);
		}
		catch (Exception e) {assertTrue(e instanceof MethodNotFoundException);}

		// wrong param number
		try {
			//bean.m2(l);
			resolver.invoke(context, bean, "m2", null, new Object[] {l}); 
			assertTrue(false);
		}
		catch (Exception e) {assertTrue(e instanceof MethodNotFoundException);}

		// wrong vararg type
		try {
			//bean.m3("abc", 3);
			resolver.invoke(context, bean, "m3", null, new Object[] {"abc", 3}); 
			assertTrue(false);
		}
		catch (Exception e) {assertTrue(e instanceof MethodNotFoundException);}

		// ambiguous VarArg methods
		// define: m(long ...) and m(int ...)
		// invoke: m(Integer)
		try {
			//bean.m4(i);
			resolver.invoke(context, bean, "m4", null, new Object[] {i}); 
			assertTrue(false);
		}
		catch (Exception e) {assertTrue(e instanceof MethodNotFoundException);}

		// ambiguous VarArg methods
		// define: m(String, String ...) and m(String ...)
		// invoke: m(String)
		try {
			//bean.m5("abc");
			resolver.invoke(context, bean, "m5", null, new Object[] {"abc"}); 
			assertTrue(false);
		}
		catch (Exception e) {assertTrue(e instanceof MethodNotFoundException);}

		// 2D array
		// expecting: 	Integer[][]
		// actual:		Object[] {Integer[], String[]}
		try {
			resolver.invoke(context, bean, "m6", null, new Object[] {new Object[] {int_arr, obj_arr}});
		}
		catch (Exception e) {assertTrue(e instanceof MethodNotFoundException);}
	}



	public void testGetMethodUsingPhase2() {
		@SuppressWarnings("unused")
		class TestGetMethodUsingPhase2Class {
			public String m(Object o, long l) {
				return String.format("TestGetMethodUsingPhase0.m(Object %s, long %s)", o, l);
			}

			public String mAmbiguous(Object o, long l) {
				return String.format("TestGetMethodUsingPhase0.m(Object %s, long %s)", o, l);
			}
			public String m(Object o, double l) {
				return String.format("TestGetMethodUsingPhase0.m(Object %s, double %s)", o, l);
			}

			public String m(Object o, Long l) {
				return String.format("TestGetMethodUsingPhase2Class.m(Object %s, Long %s", o, l);
			}

			public String m1(String s) {
				return String.format("TestGetMethodUsingPhase2Class.m(String %s)", s);
			}

			public String m1(String ... s) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase2Class");
				for (String ss: s) {
					sb.append(ss).append(";");
				}
				return sb.toString();
			}

		}

		BeanELResolver resolver = new BeanELResolver();
		TestGetMethodUsingPhase2Class bean = new TestGetMethodUsingPhase2Class();

		Long l = Long.valueOf(3);
		Integer i = Integer.valueOf(2);
		String s = new String("abc");

		// unboxing: Long -> long
		// m(Object o, long l) is more specific than m(Object o, double l)
		Object e = bean.m(null, l);
		Object a  = resolver.invoke(context, bean, "m", null, new Object[]{null, l}); 
		assertEquals(e, a);

		// unboxing + widening: Integer -> long
		// m(Object o, long l) is more specific than m(Object o, double l)
		e = bean.m(null, i);
		a = resolver.invoke(context, bean, "m", null, new Object[]{null, i}); 
		assertEquals(e, a);

//		// boxing: -- Removed (All primitive data types are autoboxed in ELResolver, therefore, nothing to autobox)
//		prop = createProperty("#{rtCfg.class.m(null, 1)}");
//		value = (String) prop.getValue(true);
//		assertEquals(clazz.m(null, Long.valueOf(1)), value);

		// define:
		// 		1. m(String ...)
		// 		2. m(String)
		// invoke:
		//		m("abc") -> invoke method 2
		e = bean.m1(s);
		a  = resolver.invoke(context, bean, "m1", null, new Object[]{s}); 
		assertEquals(e, a);
	}


	public void testGetMethodUsingPhase2Star() {
		class TestGetMethodUsingPhase2StarClass {
			public String m(Integer i) {
				return "TestGetMethodUsingPhase2Star:m(Integer " + i + ")";
			}

			public String m2(String s) {
				return "TestGetMethodUsingPhase2Star:m(String " + s + ")";
			}

			public String m3(Integer[] i) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase2StarClass:m3(");
				for (Integer in : i) {
					sb.append(in).append(";");
				}
				sb.append(")");
				return sb.toString();
			}

			public String m4(Integer i, Integer i2) {
				return "TestGetMethodUsingPhase2StarClass:m4(" + i + "," + i2 + ")";
			}

			public String m5(Object[][] double_arr) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase2StarClass:m5(");
				for (Object[] arr: double_arr) {
					sb.append("[");
					for (Object o: arr) {
						sb.append(o).append(";");
					}
					sb.append("]");
				}
				sb.append(")");
				return sb.toString();
			}
		}

		BeanELResolver resolver = new BeanELResolver();
		TestGetMethodUsingPhase2StarClass bean = new TestGetMethodUsingPhase2StarClass();
		
		Long l = Long.valueOf(3);
		Map<String, String> map = new HashMap<String, String>();
		map.put("key1", "value1");
		map.put("key2", "value2");
		Object[] obj_arr = new Object[] {Integer.valueOf(1), "12"};
		Object[][] double_arr = new Object[][] {
			new Object[] {"123", Long.valueOf(1)},
			obj_arr
		};

		// Long -> Integer
		Object e = bean.m(Integer.valueOf(l.intValue()));
		Object a  = resolver.invoke(context, bean, "m", null, new Object[]{l}); 
		assertEquals(e, a);

		// Map -> String
		e = bean.m2(map.toString());
		a  = resolver.invoke(context, bean, "m2", null, new Object[]{map}); 
		assertEquals(e, a);

		// array coerce: new Object[] {Integer.getValue(1), "12"} -> Integer[]
		e = bean.m3(new Integer[] {Integer.valueOf(1), 12});
		a  = resolver.invoke(context, bean, "m3", null, new Object[]{obj_arr}); 
		assertEquals(e, a);

		// call Integer[] with int[]
		int[] intArr = new int[] {1, 2, 3};
		Integer[] integerArr = new Integer[] {Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3)};
		e = bean.m3(integerArr);
		a = resolver.invoke(context, bean, "m3", null, new Object[] {intArr});
		assertEquals(e, a);

		// do coerce, and one of the param is null
		e = bean.m4(12, null);
		a  = resolver.invoke(context, bean, "m4", null, new Object[]{"12", null}); 
		assertEquals(e, a);

		/*
		 * multidimensional array coerce:
		 * require:
		 * 			Long[][]
		 * actual param:
		 * 			Object[2][2] = [ "123" , Long.valueOf(1)] [Integer.getValue(1), "12"]
		 */
		e = bean.m5(double_arr);
		a  = resolver.invoke(context, bean, "m5", null, new Object[]{double_arr}); 
		assertEquals(e, a);
	}

	// Star Phases in BeanELResolve handles JUEL Coerce.
	// Test method calls that require Coerce are located in star phase indeed. 
	public void testStarPhaseInvocation() throws Exception {
		class TestExpressionFactory extends ExpressionFactory {

			@Override
			public Object coerceToType(Object paramObject, Class<?> paramClass) {
				throw new UnsupportedOperationException();
			}

			@Override
			public MethodExpression createMethodExpression(ELContext paramELContext,
					String paramString, Class<?> paramClass, Class<?>[] paramArrayOfClass) {
				throw new UnsupportedOperationException();
			}

			@Override
			public ValueExpression createValueExpression(ELContext paramELContext,
					String paramString, Class<?> paramClass) {
				throw new UnsupportedOperationException();
			}

			@Override
			public ValueExpression createValueExpression(Object paramObject, Class<?> paramClass) {
				throw new UnsupportedOperationException();
			}
		}
		class TestContext extends ELContext {
			@Override
			public ELResolver getELResolver() {
				throw new UnsupportedOperationException();
			}

			@Override
			public FunctionMapper getFunctionMapper() {
				throw new UnsupportedOperationException();
			}

			@Override
			public VariableMapper getVariableMapper() {
				throw new UnsupportedOperationException();
			}
		}
		@SuppressWarnings("unused")
		class TestClass {
			public void m(String s) {}
			public void n(String ... s) {}
		}

		ExpressionFactory factory = new TestExpressionFactory();
		ELContext context = new TestContext();
		context.putContext(ExpressionFactory.class, factory);
		BeanELResolver resolver = new BeanELResolver();
		TestClass clazz = new TestClass();

		try {
			resolver.invoke(context, clazz, "m", null, new Object[] {new HashMap<String, String>()});
			assertTrue(false);
		}
		catch (MethodNotFoundException e) {}
		catch (Exception e) {
			e.printStackTrace();
			assertTrue(false);
		}

		try {
			resolver.invoke(context, clazz, "n", null, new Object[] {"a", 1});
			assertTrue(false);
		}
		catch (MethodNotFoundException e) {}
		catch (Exception e) {
			assertTrue(false);
		}
	}


	public void testGetMethodUsingPhase3() {
		@SuppressWarnings("unused")
		final class TestGetMethodUsingPhase3Class {
			public String m(Integer ... i) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase3:m(");
				for (Integer ii : i) {
					sb.append(ii).append(":");
				}
				sb.append(")");
				return sb.toString();
			}

			public String m1(long ... l) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase3Class.m1(long ");
				for (long ll: l) {
					sb.append(ll).append(";");
				}
				sb.append(")");
				return sb.toString();
			}

			public String m1(Object ... o) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase3Class.m1(Object ");
				for (Object oo: o) {
					sb.append(oo).append(";");
				}
				sb.append(")");
				return sb.toString();
			}

			public String m2(long ... l) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase3Class.m2(");
				for (long ll: l) {
					sb.append(l).append(";");
				}
				sb.append(")");
				return sb.toString();
			}

			public String m2(int ... i) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase3Class.m2(");
				for (int ii: i) {
					sb.append(ii).append(";");
				}
				sb.append(")");
				return sb.toString();
			}
		}
		/*
		 * Part 1: Call Vararg in individual elements.
		 * 	e.g.: void m(int ...), and invoke m(1, 2, 3)
		 *
		 * Part 2: Put all VarArg arguments in an array
		 * 	e.g.: void m(int ...), and invoke m(new int[]{1, 2, 3})
		 */
		BeanELResolver resolver = new BeanELResolver();
		TestGetMethodUsingPhase3Class bean = new TestGetMethodUsingPhase3Class();

		Integer int1 = Integer.valueOf(1);
		Integer int2 = Integer.valueOf(2);

		Integer[] int_array = new Integer[] {Integer.valueOf(1), Integer.valueOf(2)};

		Object e = bean.m();
		Object a  = resolver.invoke(context, bean, "m", null, null); 
		assertEquals(e, a);
		
		e = bean.m(int1, int2);
		a  = resolver.invoke(context, bean, "m", null, new Object[] {int_array}); 
		assertEquals(e, a);
		e = bean.m(int1, int2);
		a  = resolver.invoke(context, bean, "m", null, int_array); 
		assertEquals(e, a);

		e = bean.m(int_array);
		a  = resolver.invoke(context, bean, "m", null, new Object[] {int_array}); 
		assertEquals(e, a);
		e = bean.m(int_array);
		a  = resolver.invoke(context, bean, "m", null, int_array); 
		assertEquals(e, a);

		/*
		 * defined:
		 * 		1. m(long ...)
		 * 		2. m(Object ...)
		 * invoke:
		 * 		m(Integer) -> goes to 1
		 */
		e = bean.m(int1);
		a  = resolver.invoke(context, bean, "m", null, new Object[] {int1}); 
		assertEquals(e, a);
	}

	public void testGetMethodUsingPhase3Star() {
		final class TestGetMethodUsingPhase3StarClass {
			public String m(Integer ... i) {
				return "TestGetMethodUsingPhase3:m(Integer ... i)";
			}

			public String m2(Integer i1, Integer ... i) {
				StringBuilder sb = new StringBuilder("TestGetMethodUsingPhase3StarClass:m2");
				sb.append("(").append(i1).append(",");
				for (Integer ii: i) {
					sb.append(ii).append(",");
				}
				sb.append(")");
				return sb.toString();
			}
		}
		/*
		 * Part 1: Call Vararg in individual elements.
		 * 	e.g.: void m(int ...), and invoke m('1', '2', '3')
		 *
		 * Pass an array as argument won't come into Phase3*. Since
		 * JUEL doesn't coerceToType array at all.s
		 */
		BeanELResolver resolver = new BeanELResolver();
		TestGetMethodUsingPhase3StarClass bean = new TestGetMethodUsingPhase3StarClass();

		Integer i = Integer.valueOf(3);
		Object[] obj_arr = new Object[] {i, "1", 123l};

		Object e = bean.m(1, 2);
		Object a  = resolver.invoke(context, bean, "m", null, new Object[] {"1", "2"}); 
		assertEquals(e, a);

		/*
		 * void m(Integer, Integer ...), and invoke m("1", new Object[] {Integer.valueOf(3), "1", 123123l});
		 */
		e = bean.m2(1, new Integer[] {i, 1, 123});
		a  = resolver.invoke(context, bean, "m2", null, new Object[] { "1", obj_arr}); 
		assertEquals(e, a);

		// void m(Integer, Integer ...), and invoke m("1", Integer.valueOf(3), "1", 123123l);
		e = bean.m2(1, i, 1, 123);
		a  = resolver.invoke(context, bean, "m2", null, new Object[] { "1", i, "1", 123}); 
		assertEquals(e, a);
	}
	
	public void testInvoke2() {
		BeanELResolver resolver = new BeanELResolver();
		assertEquals(42, resolver.invoke(context, new TestClass().getAnonymousTestInterface(), "getFourtyTwo", null, new Class[]{}));
		assertEquals(42, resolver.invoke(context, new TestClass().getNestedTestInterface(), "getFourtyTwo", null, new Class[]{}));
		assertEquals(42, resolver.invoke(context, new TestClass().getNestedTestInterface2(), "getFourtyTwo", null, new Class[]{}));
	}
}
