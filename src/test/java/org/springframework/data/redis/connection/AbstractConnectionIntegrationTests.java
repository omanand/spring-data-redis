/*
 * Copyright 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.redis.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.springframework.data.redis.SpinBarrier.waitFor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.RedisTestProfileValueSource;
import org.springframework.data.redis.RedisVersionUtils;
import org.springframework.data.redis.TestCondition;
import org.springframework.data.redis.connection.RedisListCommands.Position;
import org.springframework.data.redis.connection.RedisStringCommands.BitOperation;
import org.springframework.data.redis.connection.RedisZSetCommands.Aggregate;
import org.springframework.data.redis.connection.RedisZSetCommands.Tuple;
import org.springframework.data.redis.connection.SortParameters.Order;
import org.springframework.data.redis.connection.StringRedisConnection.StringTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationUtils;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.annotation.IfProfileValue;
import org.springframework.test.annotation.ProfileValueSourceConfiguration;

/**
 * Base test class for AbstractConnection integration tests
 *
 * @author Costin Leau
 * @author Jennifer Hickey
 *
 */
@ProfileValueSourceConfiguration(RedisTestProfileValueSource.class)
public abstract class AbstractConnectionIntegrationTests {

	protected StringRedisConnection connection;
	protected RedisSerializer<Object> serializer = new JdkSerializationRedisSerializer();
	protected RedisSerializer<String> stringSerializer = new StringRedisSerializer();

	private static final byte[] EMPTY_ARRAY = new byte[0];

	protected List<Object> actual = new ArrayList<Object>();

	@Autowired
	protected RedisConnectionFactory connectionFactory;

	protected RedisConnection byteConnection;

	@Before
	public void setUp() {
		byteConnection = connectionFactory.getConnection();
		connection = new DefaultStringRedisConnection(byteConnection);
		initConnection();
	}

	@After
	public void tearDown() {
		try {
			connection.flushDb();
		} catch (Exception e) {
			// Connection may be closed in certain cases, like after pub/sub
			// tests
		}
		connection.close();
		connection = null;
	}
	
	public void testSelect() {
		// Make sure this doesn't throw Exception
		connection.select(1);
	}

	@Test
	@IfProfileValue(name = "runLongTests", value = "true")
	public void testExpire() throws Exception {
		connection.set("exp", "true");
		actual.add(connection.expire("exp", 1));
		verifyResults(Arrays.asList(new Object[] { true }));
		assertTrue(waitFor(new KeyExpired("exp"), 3000l));
	}

	@Test
	@IfProfileValue(name = "runLongTests", value = "true")
	public void testExpireAt() throws Exception {
		connection.set("exp2", "true");
		actual.add(connection.expireAt("exp2", System.currentTimeMillis() / 1000 + 1));
		verifyResults(Arrays.asList(new Object[] { true }));
		assertTrue(waitFor(new KeyExpired("exp2"), 3000l));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testPExpire() {
		connection.set("exp", "true");
		actual.add(connection.pExpire("exp", 100));
		verifyResults(Arrays.asList(new Object[] { true }));
		assertTrue(waitFor(new KeyExpired("exp"), 1000l));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testPExpireKeyNotExists() {
		actual.add(connection.pExpire("nonexistent", 100));
		verifyResults(Arrays.asList(new Object[] { false }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testPExpireAt() {
		connection.set("exp2", "true");
		actual.add(connection.pExpireAt("exp2", System.currentTimeMillis() + 200));
		verifyResults(Arrays.asList(new Object[] { true }));
		assertTrue(waitFor(new KeyExpired("exp2"), 1000l));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testPExpireAtKeyNotExists() {
		actual.add(connection.pExpireAt("nonexistent", System.currentTimeMillis() + 200));
		verifyResults(Arrays.asList(new Object[] { false }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testScriptLoadEvalSha() {
		getResults();
		String sha1 = connection.scriptLoad("return KEYS[1]");
		initConnection();
		actual.add(connection.evalSha(sha1, ReturnType.VALUE, 2, "key1", "key2"));
		assertEquals("key1", new String((byte[]) getResults().get(0)));
	}

	@SuppressWarnings("unchecked")
	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalShaArrayStrings() {
		getResults();
		String sha1 = connection.scriptLoad("return {KEYS[1],ARGV[1]}");
		initConnection();
		actual.add(connection.evalSha(sha1, ReturnType.MULTI, 1, "key1", "arg1"));
		List<Object> results = getResults();
		List<byte[]> scriptResults = (List<byte[]>) results.get(0);
		assertEquals(
				Arrays.asList(new Object[] { "key1", "arg1" }),
				Arrays.asList(new Object[] { new String(scriptResults.get(0)),
						new String(scriptResults.get(1)) }));
	}

	@Test(expected = RedisSystemException.class)
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalShaNotFound() {
		connection.evalSha("somefakesha", ReturnType.VALUE, 2, "key1", "key2");
		getResults();
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnString() {
		actual.add(connection.eval("return KEYS[1]", ReturnType.VALUE, 1, "foo"));
		byte[] result = (byte[]) getResults().get(0);
		assertEquals("foo", new String(result));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnNumber() {
		actual.add(connection.eval("return 10", ReturnType.INTEGER, 0));
		verifyResults(Arrays.asList(new Object[] { 10l }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnSingleOK() {
		actual.add(connection.eval("return redis.call('set','abc','ghk')", ReturnType.STATUS, 0));
		assertEquals(Arrays.asList(new Object[] { "OK" }), getResults());
	}

	@Test(expected = RedisSystemException.class)
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnSingleError() {
		connection.eval("return redis.call('expire','foo')", ReturnType.BOOLEAN, 0);
		getResults();
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnFalse() {
		actual.add(connection.eval("return false", ReturnType.BOOLEAN, 0));
		verifyResults(Arrays.asList(new Object[] { false }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnTrue() {
		actual.add(connection.eval("return true", ReturnType.BOOLEAN, 0));
		verifyResults(Arrays.asList(new Object[] { true }));
	}

	@SuppressWarnings("unchecked")
	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnArrayStrings() {
		actual.add(connection.eval("return {KEYS[1],ARGV[1]}", ReturnType.MULTI, 1, "foo", "bar"));
		List<byte[]> result = (List<byte[]>) getResults().get(0);
		assertEquals(Arrays.asList(new Object[] { "foo", "bar" }), Arrays.asList(new Object[] {
				new String(result.get(0)), new String(result.get(1)) }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnArrayNumbers() {
		actual.add(connection.eval("return {1,2}", ReturnType.MULTI, 1, "foo", "bar"));
		verifyResults(Arrays.asList(new Object[] { Arrays.asList(new Object[] { 1l, 2l }) }));
	}

	@SuppressWarnings("unchecked")
	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnArrayOKs() {
		actual.add(connection.eval(
				"return { redis.call('set','abc','ghk'),  redis.call('set','abc','lfdf')}",
				ReturnType.MULTI, 0));
		List<byte[]> result = (List<byte[]>) getResults().get(0);
		assertEquals(Arrays.asList(new Object[] { "OK", "OK" }), Arrays.asList(new Object[] {
				new String(result.get(0)), new String(result.get(1)) }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnArrayFalses() {
		actual.add(connection.eval("return { false, false}", ReturnType.MULTI, 0));
		verifyResults(Arrays.asList(new Object[] { Arrays.asList(new Object[] { null, null }) }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testEvalReturnArrayTrues() {
		actual.add(connection.eval("return { true, true}", ReturnType.MULTI, 0));
		verifyResults(Arrays.asList(new Object[] { Arrays.asList(new Object[] { 1l, 1l }) }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testScriptExists() {
		getResults();
		String sha1 = connection.scriptLoad("return 'foo'");
		initConnection();
		actual.add(connection.scriptExists(sha1, "98777234"));
		verifyResults(Arrays.asList(new Object[] { Arrays.asList(new Object[] { true, false }) }));
	}

	@Test
	@IfProfileValue(name = "runLongTests", value = "true")
	public void testScriptKill() throws Exception {
		getResults();
		assumeTrue(RedisVersionUtils.atLeast("2.6", byteConnection));
		initConnection();
		final AtomicBoolean scriptDead = new AtomicBoolean(false);
		Thread th = new Thread(new Runnable() {
			public void run() {
				DefaultStringRedisConnection conn2 = new DefaultStringRedisConnection(
						connectionFactory.getConnection());
				try {
					conn2.eval("local time=1 while time < 10000000000 do time=time+1 end",
							ReturnType.BOOLEAN, 0);
				} catch (DataAccessException e) {
					scriptDead.set(true);
				}
				conn2.close();
			}
		});
		th.start();
		Thread.sleep(1000);
		connection.scriptKill();
		getResults();
		assertTrue(waitFor(new TestCondition() {
			public boolean passes() {
				return scriptDead.get();
			}
		}, 3000l));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testScriptFlush() {
		getResults();
		String sha1 = connection.scriptLoad("return KEYS[1]");
		connection.scriptFlush();
		initConnection();
		actual.add(connection.scriptExists(sha1));
		verifyResults(Arrays.asList(new Object[] { Arrays.asList(new Object[] { false }) }));
	}

	@Test
	@IfProfileValue(name = "runLongTests", value = "true")
	public void testPersist() throws Exception {
		connection.set("exp3", "true");
		actual.add(connection.expire("exp3", 1));
		actual.add(connection.persist("exp3"));
		Thread.sleep(1500);
		actual.add(connection.exists("exp3"));
		verifyResults(Arrays.asList(new Object[] { true, true, true }));
	}

	@Test
	@IfProfileValue(name = "runLongTests", value = "true")
	public void testSetEx() throws Exception {
		connection.setEx("expy", 1l, "yep");
		actual.add(connection.get("expy"));
		verifyResults(Arrays.asList(new Object[] { "yep" }));
		assertTrue(waitFor(new KeyExpired("expy"), 2500l));
	}

	@Test
	@IfProfileValue(name = "runLongTests", value = "true")
	public void testBRPopTimeout() throws Exception {
		actual.add(connection.bRPop(1, "alist"));
		Thread.sleep(1500l);
		verifyResults(Arrays.asList(new Object[] { null }));
	}

	@Test
	@IfProfileValue(name = "runLongTests", value = "true")
	public void testBLPopTimeout() throws Exception {
		actual.add(connection.bLPop(1, "alist"));
		Thread.sleep(1500l);
		verifyResults(Arrays.asList(new Object[] { null }));
	}

	@Test
	@IfProfileValue(name = "runLongTests", value = "true")
	public void testBRPopLPushTimeout() throws Exception {
		actual.add(connection.bRPopLPush(1, "alist", "foo"));
		Thread.sleep(1500l);
		verifyResults(Arrays.asList(new Object[] { null }));
	}

	@Test
	public void testSetAndGet() {
		String key = "foo";
		String value = "blabla";
		connection.set(key.getBytes(), value.getBytes());
		actual.add(connection.get(key));
		verifyResults(new ArrayList<Object>(Collections.singletonList(value)));
	}

	@Test
	public void testPingPong() throws Exception {
		actual.add(connection.ping());
		verifyResults(new ArrayList<Object>(Collections.singletonList("PONG")));
	}

	@Test
	public void testBitSet() throws Exception {
		String key = "bitset-test";
		connection.setBit(key, 0, false);
		connection.setBit(key, 1, true);
		actual.add(connection.getBit(key, 0));
		actual.add(connection.getBit(key, 1));
		verifyResults(Arrays.asList(new Object[] { false, true }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testBitCount() {
		String key = "bitset-test";
		connection.setBit(key, 0, false);
		connection.setBit(key, 1, true);
		connection.setBit(key, 2, true);
		actual.add(connection.bitCount(key));
		verifyResults(new ArrayList<Object>(Collections.singletonList(2l)));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testBitCountInterval() {
		connection.set("mykey", "foobar");
		actual.add(connection.bitCount("mykey", 1, 1));
		verifyResults(new ArrayList<Object>(Collections.singletonList(6l)));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testBitCountNonExistentKey() {
		actual.add(connection.bitCount("mykey"));
		verifyResults(new ArrayList<Object>(Collections.singletonList(0l)));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testBitOpAnd() {
		connection.set("key1", "foo");
		connection.set("key2", "bar");
		actual.add(connection.bitOp(BitOperation.AND, "key3", "key1", "key2"));
		actual.add(connection.get("key3"));
		verifyResults(Arrays.asList(new Object[] { 3l, "bab" }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testBitOpOr() {
		connection.set("key1", "foo");
		connection.set("key2", "ugh");
		actual.add(connection.bitOp(BitOperation.OR, "key3", "key1", "key2"));
		actual.add(connection.get("key3"));
		verifyResults(Arrays.asList(new Object[] { 3l, "woo" }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testBitOpXOr() {
		connection.set("key1", "abcd");
		connection.set("key2", "efgh");
		actual.add(connection.bitOp(BitOperation.XOR, "key3", "key1", "key2"));
		verifyResults(Arrays.asList(new Object[] { 4l }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testBitOpNot() {
		connection.set("key1", "abcd");
		actual.add(connection.bitOp(BitOperation.NOT, "key3", "key1"));
		verifyResults(Arrays.asList(new Object[] { 4l }));
	}

	@Test(expected = RedisSystemException.class)
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testBitOpNotMultipleSources() {
		connection.set("key1", "abcd");
		connection.set("key2", "efgh");
		actual.add(connection.bitOp(BitOperation.NOT, "key3", "key1", "key2"));
		getResults();
	}

	@Test
	public void testInfo() throws Exception {
		actual.add(connection.info());
		List<Object> results = getResults();
		Properties info = (Properties) results.get(0);
		assertTrue("at least 5 settings should be present", info.size() >= 5);
		String version = info.getProperty("redis_version");
		assertNotNull(version);
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testInfoBySection() throws Exception {
		actual.add(connection.info("server"));
		List<Object> results = getResults();
		Properties info = (Properties) results.get(0);
		assertTrue("at least 5 settings should be present", info.size() >= 5);
		String version = info.getProperty("redis_version");
		assertNotNull(version);
	}

	@Test
	public void testNullKey() throws Exception {
		try {
			connection.decr((String) null);
			fail("Decrement should fail with null key");
		} catch (Exception ex) {
			// expected
		}
	}

	@Test
	public void testNullValue() throws Exception {
		byte[] key = UUID.randomUUID().toString().getBytes();
		connection.append(key, EMPTY_ARRAY);
		try {
			connection.append(key, null);
			fail("Append should fail with null value");
		} catch (DataAccessException ex) {
			// expected
		}
	}

	@Test
	public void testHashNullKey() throws Exception {
		byte[] key = UUID.randomUUID().toString().getBytes();
		try {
			connection.hExists(key, null);
			fail("hExists should fail with null key");
		} catch (DataAccessException ex) {
			// expected
		}
	}

	@Test
	public void testHashNullValue() throws Exception {
		byte[] key = UUID.randomUUID().toString().getBytes();
		byte[] field = "random".getBytes();

		connection.hSet(key, field, EMPTY_ARRAY);
		try {
			connection.hSet(key, field, null);
			fail("hSet should fail with null value");
		} catch (DataAccessException ex) {
			// expected
		}
	}

	@Test
	public void testNullSerialization() throws Exception {
		String[] keys = new String[] { "~", "[" };
		actual.add(connection.mGet(keys));
		verifyResults(Arrays.asList(new Object[] { Arrays.asList(new String[] { null, null }) }));
		StringRedisTemplate stringTemplate = new StringRedisTemplate(connectionFactory);
		List<String> multiGet = stringTemplate.opsForValue().multiGet(Arrays.asList(keys));
		assertEquals(Arrays.asList(new String[] { null, null }), multiGet);
	}

	@Test
	public void testAppend() {
		connection.set("a", "b");
		actual.add(connection.append("a", "c"));
		actual.add(connection.get("a"));
		verifyResults(Arrays.asList(new Object[] { 2l, "bc" }));
	}

	@Test
	public void testPubSubWithNamedChannels() throws Exception {
		final String expectedChannel = "channel1";
		final String expectedMessage = "msg";
		final BlockingDeque<Message> messages = new LinkedBlockingDeque<Message>();

		MessageListener listener = new MessageListener() {
			public void onMessage(Message message, byte[] pattern) {
				messages.add(message);
				System.out.println("Received message '" + new String(message.getBody()) + "'");
			}
		};

		Thread th = new Thread(new Runnable() {
			public void run() {
				// sleep 1/2 second to let the registration happen
				try {
					Thread.sleep(500);
				} catch (InterruptedException ex) {
					throw new RuntimeException(ex);
				}

				// open a new connection
				RedisConnection connection2 = connectionFactory.getConnection();
				connection2.publish(expectedChannel.getBytes(), expectedMessage.getBytes());
				connection2.close();
				// In some clients, unsubscribe happens async of message
				// receipt, so not all
				// messages may be received if unsubscribing now.
				// Connection.close in teardown
				// will take care of unsubscribing.
				if (!(ConnectionUtils.isAsync(connectionFactory))) {
					connection.getSubscription().unsubscribe();
				}
			}
		});

		th.start();
		connection.subscribe(listener, expectedChannel.getBytes());
		// Not all providers block on subscribe, give some time for messages to
		// be received
		Message message = messages.poll(5, TimeUnit.SECONDS);
		assertNotNull(message);
		assertEquals(expectedMessage, new String(message.getBody()));
		assertEquals(expectedChannel, new String(message.getChannel()));
	}

	@Test
	public void testPubSubWithPatterns() throws Exception {
		final String expectedPattern = "channel*";
		final String expectedMessage = "msg";
		final BlockingDeque<Message> messages = new LinkedBlockingDeque<Message>();

		final MessageListener listener = new MessageListener() {
			public void onMessage(Message message, byte[] pattern) {
				assertEquals(expectedPattern, new String(pattern));
				messages.add(message);
				System.out.println("Received message '" + new String(message.getBody()) + "'");
			}
		};

		Thread th = new Thread(new Runnable() {
			public void run() {
				// sleep 1/2 second to let the registration happen
				try {
					Thread.sleep(500);
				} catch (InterruptedException ex) {
					throw new RuntimeException(ex);
				}

				// open a new connection
				RedisConnection connection2 = connectionFactory.getConnection();
				connection2.publish("channel1".getBytes(), expectedMessage.getBytes());
				connection2.publish("channel2".getBytes(), expectedMessage.getBytes());
				connection2.close();
				// In some clients, unsubscribe happens async of message
				// receipt, so not all
				// messages may be received if unsubscribing now.
				// Connection.close in teardown
				// will take care of unsubscribing.
				if (!(ConnectionUtils.isAsync(connectionFactory))) {
					connection.getSubscription().pUnsubscribe(expectedPattern.getBytes());
				}
			}
		});

		th.start();
		connection.pSubscribe(listener, expectedPattern);
		// Not all providers block on subscribe (Lettuce does not), give some
		// time for messages to be received
		Message message = messages.poll(5, TimeUnit.SECONDS);
		assertNotNull(message);
		assertEquals(expectedMessage, new String(message.getBody()));
		message = messages.poll(5, TimeUnit.SECONDS);
		assertNotNull(message);
		assertEquals(expectedMessage, new String(message.getBody()));
	}

	@Test(expected = DataAccessException.class)
	public void exceptionExecuteNative() throws Exception {
		connection.execute("set", "foo");
		connection.execute("ZadD", getClass() + "#foo\t0.90\titem");
		getResults();
	}

	@Test
	public void testExecute() {
		connection.set("foo", "bar");
		actual.add(connection.execute("GET", "foo"));
		assertEquals("bar", stringSerializer.deserialize((byte[]) getResults().get(0)));
	}

	@Test
	public void testExecuteNoArgs() {
		actual.add(connection.execute("PING"));
		List<Object> results = getResults();
		assertEquals("PONG", stringSerializer.deserialize((byte[])results.get(0)));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testMultiExec() throws Exception {
		connection.multi();
		connection.set("key", "value");
		connection.get("key");
		actual.add(connection.exec());
		List<Object> results = getResults();
		List<Object> execResults = (List<Object>) results.get(0);
		assertEquals(2, execResults.size());
		assertEquals("value", new String((byte[]) execResults.get(1)));
		assertEquals("value", connection.get("key"));
	}

	@Test
	public void testMultiAlreadyInTx() throws Exception {
		connection.multi();
		// Ensure it's OK to call multi twice
		testMultiExec();
	}

	@Test(expected=RedisSystemException.class)
	public void testExecWithoutMulti() {
		connection.exec();
		getResults();
	}

	@Test(expected=RedisSystemException.class)
	public void testErrorInTx() {
		connection.multi();
		connection.set("foo","bar");
		// Try to do a list op on a value
		connection.lPop("foo");
		connection.exec();
		getResults();
	}

	@Test
	public void testMultiDiscard() throws Exception {
		DefaultStringRedisConnection conn2 = new DefaultStringRedisConnection(
				connectionFactory.getConnection());
		conn2.set("testitnow", "willdo");
		connection.multi();
		connection.set("testitnow2", "notok");
		connection.discard();
		actual.add(connection.get("testitnow"));
		List<Object> results = getResults();
		assertEquals(Arrays.asList(new String[] { "willdo" }), results);
		initConnection();
		// Ensure we can run a new tx after discarding previous one
		testMultiExec();
	}

	@Test
	public void testWatch() throws Exception {
		connection.set("testitnow", "willdo");
		connection.watch("testitnow".getBytes());
		//Give some time for watch to be asynch executed in extending tests
		Thread.sleep(500);
		DefaultStringRedisConnection conn2 = new DefaultStringRedisConnection(
				connectionFactory.getConnection());
		conn2.set("testitnow", "something");
		conn2.close();
		connection.multi();
		connection.set("testitnow", "somethingelse");
		actual.add(connection.exec());
		actual.add(connection.get("testitnow"));
		verifyResults(Arrays.asList(new Object[] { null, "something" }));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testUnwatch() throws Exception {
		connection.set("testitnow", "willdo");
		connection.watch("testitnow".getBytes());
		connection.unwatch();
		connection.multi();
		//Give some time for unwatch to be asynch executed
		Thread.sleep(500);
		DefaultStringRedisConnection conn2 = new DefaultStringRedisConnection(
				connectionFactory.getConnection());
		conn2.set("testitnow", "something");
		connection.set("testitnow", "somethingelse");
		connection.get("testitnow");
		actual.add(connection.exec());
		List<Object> results = getResults();
		List<Object> execResults = (List<Object>) results.get(0);
		assertEquals("somethingelse", new String((byte[]) execResults.get(1)));
	}

	@Test
	public void testSort() {
		actual.add(connection.rPush("sortlist", "foo"));
		actual.add(connection.rPush("sortlist", "bar"));
		actual.add(connection.rPush("sortlist", "baz"));
		actual.add(connection.sort("sortlist", new DefaultSortParameters(null, Order.ASC, true)));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l,
						Arrays.asList(new String[] { "bar", "baz", "foo" }) }));
	}

	@Test
	public void testSortStore() {
		actual.add(connection.rPush("sortlist", "foo"));
		actual.add(connection.rPush("sortlist", "bar"));
		actual.add(connection.rPush("sortlist", "baz"));
		actual.add(connection.sort("sortlist", new DefaultSortParameters(null, Order.ASC, true),
				"newlist"));
		actual.add(connection.lRange("newlist", 0, 9));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l, 3l,
						Arrays.asList(new String[] { "bar", "baz", "foo" }) }));
	}

	@Test
	public void testSortNullParams() {
		actual.add(connection.rPush("sortlist", "5"));
		actual.add(connection.rPush("sortlist", "2"));
		actual.add(connection.rPush("sortlist", "3"));
		actual.add(connection.sort("sortlist", null));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l,
						Arrays.asList(new String[] { "2", "3", "5" }) }));
	}

	@Test
	public void testSortStoreNullParams() {
		actual.add(connection.rPush("sortlist", "9"));
		actual.add(connection.rPush("sortlist", "3"));
		actual.add(connection.rPush("sortlist", "5"));
		actual.add(connection.sort("sortlist", null, "newlist"));
		actual.add(connection.lRange("newlist", 0, 9));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l, 3l,
						Arrays.asList(new String[] { "3", "5", "9" }) }));
	}

	@Test
	public void testDbSize() {
		connection.set("dbparam", "foo");
		actual.add(connection.dbSize());
		assertTrue((Long) getResults().get(0) > 0);
	}

	@Test
	public void testFlushDb() {
		connection.flushDb();
		actual.add(connection.dbSize());
		verifyResults(Arrays.asList(new Object[] { 0l }));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testGetConfig() {
		actual.add(connection.getConfig("*"));
		List<String> config = (List<String>) getResults().get(0);
		assertTrue(!config.isEmpty());
	}

	@Test
	public void testEcho() {
		actual.add(connection.echo("Hello World"));
		verifyResults(Arrays.asList(new Object[] { "Hello World" }));
	}

	@Test
	public void testExists() {
		connection.set("existent", "true");
		actual.add(connection.exists("existent"));
		actual.add(connection.exists("nonexistent"));
		verifyResults(Arrays.asList(new Object[] { true, false }));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testKeys() throws Exception {
		connection.set("keytest", "true");
		actual.add(connection.keys("key*"));
		assertTrue(((Collection<String>) getResults().get(0)).contains("keytest"));
	}

	@Test
	public void testRandomKey() {
		connection.set("some", "thing");
		actual.add(connection.randomKey());
		List<Object> results = getResults();
		assertNotNull(results.get(0));
	}

	@Test
	public void testRename() {
		connection.set("renametest", "testit");
		connection.rename("renametest", "newrenametest");
		actual.add(connection.get("newrenametest"));
		actual.add(connection.exists("renametest"));
		verifyResults(Arrays.asList(new Object[] { "testit", false }));
	}

	@Test
	public void testRenameNx() {
		connection.set("nxtest", "testit");
		actual.add(connection.renameNX("nxtest", "newnxtest"));
		actual.add(connection.get("newnxtest"));
		actual.add(connection.exists("nxtest"));
		verifyResults(Arrays.asList(new Object[] { true, "testit", false }));
	}

	@Test
	public void testTtl() {
		connection.set("whatup", "yo");
		actual.add(connection.ttl("whatup"));
		verifyResults(Arrays.asList(new Object[] { -1L }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testPTtlNoExpire() {
		connection.set("whatup", "yo");
		actual.add(connection.pTtl("whatup"));
		verifyResults(Arrays.asList(new Object[] { -1L }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testPTtl() {
		connection.set("whatup", "yo");
		actual.add(connection.pExpire("whatup", 9000l));
		actual.add(connection.pTtl("whatup"));
		List<Object> results = getResults();
		assertTrue((Long) results.get(1) > -1);
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testDumpAndRestore() {
		connection.set("testing", "12");
		actual.add(connection.dump("testing".getBytes()));
		List<Object> results = getResults();
		initConnection();
		actual.add(connection.del("testing"));
		actual.add((connection.get("testing")));
		connection.restore("testing".getBytes(), 0, (byte[]) results.get(results.size() - 1));
		actual.add(connection.get("testing"));
		verifyResults(Arrays.asList(new Object[] { 1l, null, "12" }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testDumpNonExistentKey() {
		actual.add(connection.dump("fakey".getBytes()));
		verifyResults(Arrays.asList(new Object[] { null }));
	}

	@Test(expected = RedisSystemException.class)
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testRestoreBadData() {
		// Use something other than dump-specific serialization
		connection.restore("testing".getBytes(), 0, "foo".getBytes());
		getResults();
	}

	@Test(expected = RedisSystemException.class)
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testRestoreExistingKey() {
		connection.set("testing", "12");
		actual.add(connection.dump("testing".getBytes()));
		List<Object> results = getResults();
		initConnection();
		connection.restore("testing".getBytes(), 0, (byte[]) results.get(0));
		getResults();
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testRestoreTtl() {
		connection.set("testing", "12");
		actual.add(connection.dump("testing".getBytes()));
		List<Object> results = getResults();
		initConnection();
		actual.add(connection.del("testing"));
		actual.add(connection.get("testing"));
		connection.restore("testing".getBytes(), 100l, (byte[]) results.get(0));
		verifyResults(Arrays.asList(new Object[] { 1l, null }));
		assertTrue(waitFor(new KeyExpired("testing"), 300l));
	}

	@Test
	public void testDel() {
		connection.set("testing","123");
		actual.add(connection.del("testing"));
		actual.add(connection.exists("testing"));
		verifyResults(Arrays.asList(new Object[] { 1l, false }));
	}

	@Test
	public void testType() {
		connection.set("something", "yo");
		actual.add(connection.type("something"));
		verifyResults(Arrays.asList(new Object[] { DataType.STRING }));
	}

	@Test
	public void testGetSet() {
		connection.set("testGS", "1");
		actual.add(connection.getSet("testGS", "2"));
		actual.add(connection.get("testGS"));
		verifyResults(Arrays.asList(new Object[] { "1", "2" }));
	}

	@Test
	public void testMSet() {
		Map<String, String> vals = new HashMap<String, String>();
		vals.put("color", "orange");
		vals.put("size", "1");
		connection.mSetString(vals);
		actual.add(connection.mGet("color", "size"));
		verifyResults(Arrays.asList(new Object[] { Arrays.asList(new String[] { "orange", "1" }) }));
	}

	@Test
	public void testMSetNx() {
		Map<String, String> vals = new HashMap<String, String>();
		vals.put("height", "5");
		vals.put("width", "1");
		actual.add(connection.mSetNXString(vals));
		actual.add(connection.mGet("height", "width"));
		verifyResults(Arrays.asList(new Object[] { true, Arrays.asList(new String[] { "5", "1" }) }));
	}

	@Test
	public void testMSetNxFailure() {
		connection.set("height", "2");
		Map<String, String> vals = new HashMap<String, String>();
		vals.put("height", "5");
		vals.put("width", "1");
		actual.add(connection.mSetNXString(vals));
		actual.add(connection.mGet("height", "width"));
		verifyResults(Arrays.asList(new Object[] { false, Arrays.asList(new String[] { "2", null }) }));
	}

	@Test
	public void testSetNx() {
		actual.add(connection.setNX("notaround", "54"));
		actual.add(connection.get("notaround"));
		actual.add(connection.setNX("notaround", "55"));
		actual.add(connection.get("notaround"));
		verifyResults(Arrays.asList(new Object[] { true, "54", false, "54" }));
	}

	@Test
	public void testGetRangeSetRange() {
		connection.set("rangekey", "supercalifrag");
		actual.add(connection.getRange("rangekey", 0l, 2l));
		connection.setRange("rangekey", "ck", 2);
		actual.add(connection.get("rangekey"));
		verifyResults(Arrays.asList(new Object[] { "sup", "suckrcalifrag" }));
	}

	@Test
	public void testDecrByIncrBy() {
		connection.set("tdb", "4");
		actual.add(connection.decrBy("tdb", 3l));
		actual.add(connection.incrBy("tdb", 7l));
		verifyResults(Arrays.asList(new Object[] { 1l, 8l }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testIncrByDouble() {
		connection.set("tdb", "4.5");
		actual.add(connection.incrBy("tdb", 7.2));
		actual.add(connection.get("tdb"));
		verifyResults(Arrays.asList(new Object[] { 11.7d, "11.7" }));
	}

	@Test
	public void testIncrDecrByLong() {
		String key = "test.count";
		long largeNumber = 0x123456789L; // > 32bits
		connection.set(key, "0");
		actual.add(connection.incrBy(key, largeNumber));
		actual.add(connection.decrBy(key, largeNumber));
		actual.add(connection.decrBy(key, 2 * largeNumber));
		verifyResults(Arrays.asList(new Object[] {largeNumber, 0l, -2 * largeNumber}));
	}

	@Test
	public void testHashIncrDecrByLong() {
		String key = "test.hcount";
		String hkey = "hashkey";

		long largeNumber = 0x123456789L; // > 32bits
		actual.add(connection.hSet(key, hkey, "0"));
		actual.add(connection.hIncrBy(key, hkey, largeNumber));
		//assertEquals(largeNumber, Long.valueOf(connection.hGet(key, hkey)).longValue());
		actual.add(connection.hIncrBy(key, hkey, -2 * largeNumber));
		//assertEquals(-largeNumber, Long.valueOf(connection.hGet(key, hkey)).longValue());
		verifyResults(Arrays.asList(new Object[] {true, largeNumber, -largeNumber}));
	}

	@Test
	public void testIncDecr() {
		connection.set("incrtest", "0");
		actual.add(connection.incr("incrtest"));
		actual.add(connection.get("incrtest"));
		actual.add(connection.decr("incrtest"));
		actual.add(connection.get("incrtest"));
		verifyResults(Arrays.asList(new Object[] { 1l, "1", 0l, "0" }));
	}

	@Test
	public void testStrLen() {
		connection.set("strlentest", "cat");
		actual.add(connection.strLen("strlentest"));
		verifyResults(Arrays.asList(new Object[] { 3l }));
	}

	// List operations

	@Test
	public void testBLPop() {
		DefaultStringRedisConnection conn2 = new DefaultStringRedisConnection(
				connectionFactory.getConnection());
		conn2.lPush("poplist", "foo");
		conn2.lPush("poplist", "bar");
		actual.add(connection.bLPop(100, "poplist", "otherlist"));
		verifyResults(
				Arrays.asList(new Object[] {
						Arrays.asList(new String[] { "poplist", "bar" }) }));
	}

	@Test
	public void testBRPop() {
		DefaultStringRedisConnection conn2 = new DefaultStringRedisConnection(
				connectionFactory.getConnection());
		conn2.rPush("rpoplist", "bar");
		conn2.rPush("rpoplist", "foo");
		actual.add(connection.bRPop(1, "rpoplist"));
		verifyResults(
				Arrays.asList(new Object[] {
						Arrays.asList(new String[] { "rpoplist", "foo" }) }));
	}

	@Test
	public void testLInsert() {
		actual.add(connection.rPush("MyList", "hello"));
		actual.add(connection.rPush("MyList", "world"));
		actual.add(connection.lInsert("MyList", Position.AFTER, "hello", "big"));
		actual.add(connection.lRange("MyList", 0, -1));
		actual.add(connection.lInsert("MyList", Position.BEFORE, "big", "very"));
		actual.add(connection.lRange("MyList", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l,
						Arrays.asList(new String[] { "hello", "big", "world" }), 4l,
						Arrays.asList(new String[] { "hello", "very", "big", "world" }) }));
	}

	@Test
	public void testLPop() {
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.rPush("PopList", "world"));
		actual.add(connection.lPop("PopList"));
		verifyResults(Arrays.asList(new Object[] { 1l, 2l, "hello" }));
	}

	@Test
	public void testLRem() {
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.rPush("PopList", "big"));
		actual.add(connection.rPush("PopList", "world"));
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.lRem("PopList", 2, "hello"));
		actual.add(connection.lRange("PopList", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l, 4l, 2l,
						Arrays.asList(new String[] { "big", "world" }) }));
	}

	@Test
	public void testLLen() {
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.rPush("PopList", "big"));
		actual.add(connection.rPush("PopList", "world"));
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.lLen("PopList"));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l, 4l, 4l }));
	}

	@Test
	public void testLSet() {
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.rPush("PopList", "big"));
		actual.add(connection.rPush("PopList", "world"));
		connection.lSet("PopList", 1, "cruel");
		actual.add(connection.lRange("PopList", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l,
						Arrays.asList(new String[] { "hello", "cruel", "world" }) }));
	}

	@Test
	public void testLTrim() {
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.rPush("PopList", "big"));
		actual.add(connection.rPush("PopList", "world"));
		connection.lTrim("PopList", 1, -1);
		actual.add(connection.lRange("PopList", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 3l,
						Arrays.asList(new String[] { "big", "world" }) }));
	}

	@Test
	public void testRPop() {
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.rPush("PopList", "world"));
		actual.add(connection.rPop("PopList"));
		verifyResults(Arrays.asList(new Object[] { 1l, 2l, "world" }));
	}

	@Test
	public void testRPopLPush() {
		actual.add(connection.rPush("PopList", "hello"));
		actual.add(connection.rPush("PopList", "world"));
		actual.add(connection.rPush("pop2", "hey"));
		actual.add(connection.rPopLPush("PopList", "pop2"));
		actual.add(connection.lRange("PopList", 0, -1));
		actual.add(connection.lRange("pop2", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] { 1l, 2l, 1l, "world",
						Arrays.asList(new String[] { "hello" }),
						Arrays.asList(new String[] { "world", "hey" }) }));

	}

	@Test
	public void testBRPopLPush() {
		DefaultStringRedisConnection conn2 = new DefaultStringRedisConnection(
				connectionFactory.getConnection());
		conn2.rPush("PopList", "hello");
		conn2.rPush("PopList", "world");
		conn2.rPush("pop2", "hey");
		actual.add(connection.bRPopLPush(1, "PopList", "pop2"));
		List<Object> results = getResults();
		assertEquals(Arrays.asList(new String[] { "world" }), results);
		assertEquals(Arrays.asList(new String[] { "hello" }), connection.lRange("PopList", 0, -1));
		assertEquals(Arrays.asList(new String[] { "world", "hey" }),
				connection.lRange("pop2", 0, -1));
	}

	@Test
	public void testLPushX() {
		actual.add(connection.rPush("mylist", "hi"));
		actual.add(connection.lPushX("mylist", "foo"));
		actual.add(connection.lRange("mylist", 0, -1));
		verifyResults(Arrays.asList(new Object[] { 1l, 2l, Arrays.asList(new String[] { "foo", "hi" }) }));
	}

	@Test
	public void testRPushX() {
		actual.add(connection.rPush("mylist", "hi"));
		actual.add(connection.rPushX("mylist", "foo"));
		actual.add(connection.lRange("mylist", 0, -1));
		verifyResults(Arrays.asList(new Object[] { 1l, 2l, Arrays.asList(new String[] { "hi", "foo" }) }));
	}

	@Test
	public void testLIndex() {
		actual.add(connection.lPush("testylist", "foo"));
		actual.add(connection.lIndex("testylist", 0));
		verifyResults(Arrays.asList(new Object[] { 1l, "foo" }));
	}

	@Test
	public void testLPush() throws Exception {
		actual.add(connection.lPush("testlist", "bar"));
		actual.add(connection.lPush("testlist", "baz"));
		actual.add(connection.lRange("testlist", 0, -1));
		verifyResults(Arrays.asList(new Object[] { 1l, 2l,
				Arrays.asList(new String[] { "baz", "bar" }) }));
	}

	// Set operations

	@Test
	public void testSAdd() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sMembers("myset"));
		verifyResults(Arrays.asList(new Object[] { true, true, new HashSet<String>(Arrays.asList(new String[] { "foo", "bar" })) }));
	}

	@Test
	public void testSCard() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sCard("myset"));
		verifyResults(Arrays.asList(new Object[] { true, true, 2l }));
	}

	@Test
	public void testSDiff() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sAdd("otherset", "bar"));
		actual.add(connection.sDiff("myset", "otherset"));
		verifyResults(
				Arrays.asList(new Object[] { true, true, true,
						new HashSet<String>(Collections.singletonList("foo")) }));
	}

	@Test
	public void testSDiffStore() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sAdd("otherset", "bar"));
		actual.add(connection.sDiffStore("thirdset", "myset", "otherset"));
		actual.add(connection.sMembers("thirdset"));
		verifyResults(
				Arrays.asList(new Object[] { true, true, true, 1l,
						new HashSet<String>(Collections.singletonList("foo")) }));
	}

	@Test
	public void testSInter() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sAdd("otherset", "bar"));
		actual.add(connection.sInter("myset", "otherset"));
		verifyResults(
				Arrays.asList(new Object[] { true, true, true,
						new HashSet<String>(Collections.singletonList("bar")) }));
	}

	@Test
	public void testSInterStore() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sAdd("otherset", "bar"));
		actual.add(connection.sInterStore("thirdset", "myset", "otherset"));
		actual.add(connection.sMembers("thirdset"));
		verifyResults(
				Arrays.asList(new Object[] { true, true, true, 1l,
						new HashSet<String>(Collections.singletonList("bar")) }));
	}

	@Test
	public void testSIsMember() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sIsMember("myset", "foo"));
		actual.add(connection.sIsMember("myset", "baz"));
		verifyResults(Arrays.asList(new Object[] { true, true, true, false }));
	}

	@Test
	public void testSMove() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sAdd("otherset", "bar"));
		actual.add(connection.sMove("myset", "otherset", "foo"));
		verifyResults(Arrays.asList(new Object[] { true, true, true, true }));
	}

	@Test
	public void testSPop() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sPop("myset"));
		assertTrue(new HashSet<String>(Arrays.asList(new String[] { "foo", "bar" }))
				.contains((String) getResults().get(2)));
	}

	@Test
	public void testSRandMember() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sRandMember("myset"));
		assertTrue(new HashSet<String>(Arrays.asList(new String[] { "foo", "bar" }))
				.contains((String) getResults().get(2)));
	}

	@Test
	public void testSRandMemberKeyNotExists() {
		actual.add(connection.sRandMember("notexist"));
		assertNull(getResults().get(0));
	}

	@SuppressWarnings("rawtypes")
	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testSRandMemberCount() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sAdd("myset", "baz"));
		actual.add(connection.sRandMember("myset", 2));
		assertTrue(((Collection) getResults().get(3)).size() == 2);
	}

	@SuppressWarnings("rawtypes")
	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testSRandMemberCountNegative() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sRandMember("myset", -2));
		assertEquals(Arrays.asList(new String[] { "foo", "foo" }), (List) getResults().get(1));
	}

	@SuppressWarnings("rawtypes")
	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testSRandMemberCountKeyNotExists() {
		actual.add(connection.sRandMember("notexist", 2));
		assertTrue(((Collection) getResults().get(0)).isEmpty());
	}

	@Test
	public void testSRem() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sRem("myset", "foo"));
		actual.add(connection.sRem("myset", "baz"));
		actual.add(connection.sMembers("myset"));
		verifyResults(
				Arrays.asList(new Object[] { true, true, true, false,
						new HashSet<String>(Collections.singletonList("bar")) }));
	}

	@Test
	public void testSUnion() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sAdd("otherset", "bar"));
		actual.add(connection.sAdd("otherset", "baz"));
		actual.add(connection.sUnion("myset", "otherset"));
		verifyResults(Arrays.asList(new Object[] { true, true, true, true,
				new HashSet<String>(Arrays.asList(new String[] { "foo", "bar", "baz" })) }));
	}

	@Test
	public void testSUnionStore() {
		actual.add(connection.sAdd("myset", "foo"));
		actual.add(connection.sAdd("myset", "bar"));
		actual.add(connection.sAdd("otherset", "bar"));
		actual.add(connection.sAdd("otherset", "baz"));
		actual.add(connection.sUnionStore("thirdset", "myset", "otherset"));
		actual.add(connection.sMembers("thirdset"));
		verifyResults(Arrays.asList(new Object[] { true, true, true, true, 3l,
				new HashSet<String>(Arrays.asList(new String[] { "foo", "bar", "baz" })) }));
	}

	// ZSet

	@Test
	public void testZAddAndZRange() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRange("myset", 0, -1));
		verifyResults(Arrays.asList(new Object[] { true, true, new LinkedHashSet<String>(Arrays.asList(new String[] { "James", "Bob" })) }));
	}

	@Test
	public void testZCard() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zCard("myset"));
		verifyResults(Arrays.asList(new Object[] { true, true, 2l }));
	}

	@Test
	public void testZCount() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zAdd("myset", 4, "Joe"));
		actual.add(connection.zCount("myset", 1, 2));
		verifyResults(Arrays.asList(new Object[] { true, true, true, 2l }));
	}

	@Test
	public void testZIncrBy() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zAdd("myset", 4, "Joe"));
		actual.add(connection.zIncrBy("myset", 2, "Joe"));
		actual.add(connection.zRangeByScore("myset", 6, 6));
		verifyResults(
				Arrays.asList(new Object[] { true, true, true, 6d,
						new LinkedHashSet<String>(Collections.singletonList("Joe")) }));
	}

	@Test
	public void testZInterStore() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zAdd("myset", 4, "Joe"));
		actual.add(connection.zAdd("otherset", 1, "Bob"));
		actual.add(connection.zAdd("otherset", 4, "James"));
		actual.add(connection.zInterStore("thirdset", "myset", "otherset"));
		actual.add(connection.zRange("thirdset", 0, -1));
		verifyResults(Arrays.asList(new Object[] { true, true, true, true, true, 2l,
				new LinkedHashSet<String>(Arrays.asList(new String[] { "Bob", "James" })) }));
	}

	@Test
	public void testZInterStoreAggWeights() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zAdd("myset", 4, "Joe"));
		actual.add(connection.zAdd("otherset", 1, "Bob"));
		actual.add(connection.zAdd("otherset", 4, "James"));
		actual.add(connection.zInterStore("thirdset", Aggregate.MAX, new int[] { 2, 3 }, "myset",
				"otherset"));

		actual.add(connection.zRangeWithScores("thirdset", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] {
						true,
						true,
						true,
						true,
						true,
						2l,
						new LinkedHashSet<StringTuple>(Arrays.asList(new StringTuple[] {
								new DefaultStringTuple("Bob".getBytes(), "Bob", 4d),
								new DefaultStringTuple("James".getBytes(), "James", 12d) })) }));
	}

	@Test
	public void testZRangeWithScores() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRangeWithScores("myset", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] {
						true,
						true,
						new LinkedHashSet<StringTuple>(Arrays.asList(new StringTuple[] {
								new DefaultStringTuple("James".getBytes(), "James", 1d),
								new DefaultStringTuple("Bob".getBytes(), "Bob", 2d) })) }));
	}

	@Test
	public void testZRangeByScore() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRangeByScore("myset", 1, 1));
		verifyResults(Arrays.asList(new Object[] { true, true,
						new LinkedHashSet<String>(Arrays.asList(new String[] { "James" })) }));
	}

	@Test
	public void testZRangeByScoreOffsetCount() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRangeByScore("myset", 1d, 3d, 1, -1));
		verifyResults(
				Arrays.asList(new Object[] { true, true,
						new LinkedHashSet<String>(Arrays.asList(new String[] { "Bob" })) }));
	}

	@Test
	public void testZRangeByScoreWithScores() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRangeByScoreWithScores("myset", 2d, 5d));
		verifyResults(Arrays.asList(new Object[] {
				true,
				true,
				new LinkedHashSet<StringTuple>(Arrays
						.asList(new StringTuple[] { new DefaultStringTuple("Bob".getBytes(), "Bob",
								2d) })) }));
	}

	@Test
	public void testZRangeByScoreWithScoresOffsetCount() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRangeByScoreWithScores("myset", 1d, 5d, 0, 1));
		verifyResults(Arrays.asList(new Object[] {
				true,
				true,
				new LinkedHashSet<StringTuple>(Arrays
						.asList(new StringTuple[] { new DefaultStringTuple("James".getBytes(),
								"James", 1d) })) }));
	}

	@Test
	public void testZRevRange() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRevRange("myset", 0, -1));
		verifyResults(Arrays.asList(new Object[] { true, true,
				new LinkedHashSet<String>(Arrays.asList(new String[] { "Bob", "James" })) }));
	}

	@Test
	public void testZRevRangeWithScores() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRevRangeWithScores("myset", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] {
						true,
						true,
						new LinkedHashSet<StringTuple>(Arrays.asList(new StringTuple[] {
								new DefaultStringTuple("Bob".getBytes(), "Bob", 2d),
								new DefaultStringTuple("James".getBytes(), "James", 1d) })) }));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testZRevRangeByScoreOffsetCount() {
		actual.add(byteConnection.zAdd("myset".getBytes(), 2, "Bob".getBytes()));
		actual.add(byteConnection.zAdd("myset".getBytes(), 1, "James".getBytes()));
		actual.add(byteConnection.zRevRangeByScore("myset".getBytes(), 0d, 3d, 0, 5));
		assertEquals(new LinkedHashSet<String>(Arrays.asList(new String[] { "Bob", "James" })),
				SerializationUtils.deserialize((Set<byte[]>) getResults().get(2), stringSerializer));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testZRevRangeByScore() {
		actual.add(byteConnection.zAdd("myset".getBytes(), 2, "Bob".getBytes()));
		actual.add(byteConnection.zAdd("myset".getBytes(), 1, "James".getBytes()));
		actual.add(byteConnection.zRevRangeByScore("myset".getBytes(), 0d, 3d));
		assertEquals(new LinkedHashSet<String>(Arrays.asList(new String[] { "Bob", "James" })),
				SerializationUtils.deserialize((Set<byte[]>) getResults().get(2), stringSerializer));
	}

	@Test
	public void testZRevRangeByScoreWithScoresOffsetCount() {
		actual.add(byteConnection.zAdd("myset".getBytes(), 2, "Bob".getBytes()));
		actual.add(byteConnection.zAdd("myset".getBytes(), 1, "James".getBytes()));
		actual.add(byteConnection.zRevRangeByScoreWithScores("myset".getBytes(), 0d, 3d, 0, 1));
		assertEquals(
				new LinkedHashSet<Tuple>(Arrays.asList(new Tuple[] { new DefaultTuple("Bob"
						.getBytes(), 2d) })), getResults().get(2));
	}

	@Test
	public void testZRevRangeByScoreWithScores() {
		actual.add(byteConnection.zAdd("myset".getBytes(), 2, "Bob".getBytes()));
		actual.add(byteConnection.zAdd("myset".getBytes(), 1, "James".getBytes()));
		actual.add(byteConnection.zAdd("myset".getBytes(), 3, "Joe".getBytes()));
		actual.add(byteConnection.zRevRangeByScoreWithScores("myset".getBytes(), 0d, 2d));
		assertEquals(
				new LinkedHashSet<Tuple>(Arrays.asList(new Tuple[] {
						new DefaultTuple("Bob".getBytes(), 2d),
						new DefaultTuple("James".getBytes(), 1d) })), getResults().get(3));
	}

	@Test
	public void testZRank() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRank("myset", "James"));
		actual.add(connection.zRank("myset", "Bob"));
		verifyResults(Arrays.asList(new Object[] { true, true, 0l, 1l }));
	}

	@Test
	public void testZRem() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRem("myset", "James"));
		actual.add(connection.zRange("myset", 0l, -1l));
		verifyResults(
				Arrays.asList(new Object[] { true, true, true,
						new LinkedHashSet<String>(Arrays.asList(new String[] { "Bob" })) }));
	}

	@Test
	public void testZRemRangeByRank() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRemRange("myset", 0l, 3l));
		actual.add(connection.zRange("myset", 0l, -1l));
		verifyResults(Arrays.asList(new Object[] { true, true, 2l, new LinkedHashSet<String>(0) }));
	}

	@Test
	public void testZRemRangeByScore() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zRemRangeByScore("myset", 0d, 1d));
		actual.add(connection.zRange("myset", 0l, -1l));
		verifyResults(
				Arrays.asList(new Object[] { true, true, 1l,
						new LinkedHashSet<String>(Arrays.asList(new String[] { "Bob" })) }));
	}

	@Test
	public void testZRevRank() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zAdd("myset", 3, "Joe"));
		actual.add(connection.zRevRank("myset", "Joe"));
		verifyResults(Arrays.asList(new Object[] { true, true, true, 0l }));
	}

	@Test
	public void testZScore() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zAdd("myset", 3, "Joe"));
		actual.add(connection.zScore("myset", "Joe"));
		verifyResults(Arrays.asList(new Object[] { true, true, true, 3d }));
	}

	@Test
	public void testZUnionStore() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zAdd("myset", 5, "Joe"));
		actual.add(connection.zAdd("otherset", 1, "Bob"));
		actual.add(connection.zAdd("otherset", 4, "James"));
		actual.add(connection.zUnionStore("thirdset", "myset", "otherset"));
		actual.add(connection.zRange("thirdset", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] {
						true,
						true,
						true,
						true,
						true,
						3l,
						new LinkedHashSet<String>(Arrays.asList(new String[] { "Bob", "James",
								"Joe" })) }));
	}

	@Test
	public void testZUnionStoreAggWeights() {
		actual.add(connection.zAdd("myset", 2, "Bob"));
		actual.add(connection.zAdd("myset", 1, "James"));
		actual.add(connection.zAdd("myset", 4, "Joe"));
		actual.add(connection.zAdd("otherset", 1, "Bob"));
		actual.add(connection.zAdd("otherset", 4, "James"));
		actual.add(connection.zUnionStore("thirdset", Aggregate.MAX, new int[] { 2, 3 }, "myset",
				"otherset"));
		actual.add(connection.zRangeWithScores("thirdset", 0, -1));
		verifyResults(
				Arrays.asList(new Object[] {
						true,
						true,
						true,
						true,
						true,
						3l,
						new LinkedHashSet<StringTuple>(Arrays.asList(new StringTuple[] {
								new DefaultStringTuple("Bob".getBytes(), "Bob", 4d),
								new DefaultStringTuple("Joe".getBytes(), "Joe", 8d),
								new DefaultStringTuple("James".getBytes(), "James", 12d) })) }));
	}

	// Hash Ops

	@Test
	public void testHSetGet() throws Exception {
		String hash = getClass() + ":hashtest";
		String key1 = UUID.randomUUID().toString();
		String key2 = UUID.randomUUID().toString();
		String value1 = "foo";
		String value2 = "bar";
		actual.add(connection.hSet(hash, key1, value1));
		actual.add(connection.hSet(hash, key2, value2));
		actual.add(connection.hGet(hash, key1));
		actual.add(connection.hGetAll(hash));
		Map<String, String> expected = new HashMap<String, String>();
		expected.put(key1, value1);
		expected.put(key2, value2);
		verifyResults(Arrays.asList(new Object[] { true, true, value1, expected }));
	}

	@Test
	public void testHSetNX() throws Exception {
		actual.add(connection.hSetNX("myhash", "key1", "foo"));
		actual.add(connection.hSetNX("myhash", "key1", "bar"));
		actual.add(connection.hGet("myhash", "key1"));
		verifyResults(Arrays.asList(new Object[] { true, false, "foo" }));
	}

	@Test
	public void testHDel() throws Exception {
		actual.add(connection.hSet("test", "key", "val"));
		actual.add(connection.hDel("test", "key"));
		actual.add(connection.hDel("test", "foo"));
		actual.add(connection.hExists("test", "key"));
		verifyResults(Arrays.asList(new Object[] { true, true, false, false }));
	}

	@Test
	public void testHIncrBy() {
		actual.add(connection.hSet("test", "key", "2"));
		actual.add(connection.hIncrBy("test", "key", 3l));
		actual.add(connection.hGet("test", "key"));
		verifyResults(Arrays.asList(new Object[] { true, 5l, "5" }));
	}

	@Test
	@IfProfileValue(name = "redisVersion", value = "2.6")
	public void testHIncrByDouble() {
		actual.add(connection.hSet("test", "key", "2.9"));
		actual.add(connection.hIncrBy("test", "key", 3.5));
		actual.add(connection.hGet("test", "key"));
		verifyResults(Arrays.asList(new Object[] { true, 6.4d, "6.4" }));
	}

	@Test
	public void testHKeys() {
		actual.add(connection.hSet("test", "key", "2"));
		actual.add(connection.hSet("test", "key2", "2"));
		actual.add(connection.hKeys("test"));
		verifyResults(Arrays.asList(new Object[] { true, true,
						new LinkedHashSet<String>(Arrays.asList(new String[] { "key", "key2" })) }));
	}

	@Test
	public void testHLen() {
		actual.add(connection.hSet("test", "key", "2"));
		actual.add(connection.hSet("test", "key2", "2"));
		actual.add(connection.hLen("test"));
		verifyResults(Arrays.asList(new Object[] { true, true, 2l }));
	}

	@Test
	public void testHMGetSet() {
		Map<String, String> tuples = new HashMap<String, String>();
		tuples.put("key", "foo");
		tuples.put("key2", "bar");
		connection.hMSet("test", tuples);
		actual.add(connection.hMGet("test", "key", "key2"));
		verifyResults(Arrays.asList(new Object[] { Arrays.asList(new String[] { "foo", "bar" }) }));
	}

	@Test
	public void testHVals() {
		actual.add(connection.hSet("test", "key", "foo"));
		actual.add(connection.hSet("test", "key2", "bar"));
		actual.add(connection.hVals("test"));
		verifyResults(
				Arrays.asList(new Object[] { true, true,
						Arrays.asList(new String[] { "foo", "bar" }) }));
	}

	@Test
	public void testMove() {
		connection.set("foo", "bar");
		actual.add(connection.move("foo", 1));
		verifyResults(Arrays.asList(new Object[] { true}));
		connection.select(1);
		try {
			assertEquals("bar",connection.get("foo"));
		} finally {
			if(connection.exists("foo")) {
				connection.del("foo");
			}
		}
	}

	protected void verifyResults(List<Object> expected) {
		assertEquals(expected, getResults());
	}

	protected List<Object> getResults() {
		return actual;
	}

	protected void initConnection() {
		actual = new ArrayList<Object>();
	}

	protected class KeyExpired implements TestCondition {
		private String key;

		public KeyExpired(String key) {
			this.key = key;
		}

		public boolean passes() {
			return (!connection.exists(key));
		}
	}
}