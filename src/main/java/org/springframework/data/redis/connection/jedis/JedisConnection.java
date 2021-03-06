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
package org.springframework.data.redis.connection.jedis;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.FutureResult;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPipelineException;
import org.springframework.data.redis.connection.RedisSubscribedConnectionException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.SortParameters;
import org.springframework.data.redis.connection.Subscription;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

import redis.clients.jedis.BinaryJedis;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.BinaryTransaction;
import redis.clients.jedis.Builder;
import redis.clients.jedis.Client;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.Queable;
import redis.clients.jedis.Response;
import redis.clients.jedis.SortingParams;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.ZParams;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.util.Pool;

/**
 * {@code RedisConnection} implementation on top of <a href="http://github.com/xetorthio/jedis">Jedis</a> library.
 * 
 * @author Costin Leau
 */
public class JedisConnection implements RedisConnection {

	private static final Field CLIENT_FIELD;
	private static final Method SEND_COMMAND;
	private static final Method GET_RESPONSE;

	static {
		CLIENT_FIELD = ReflectionUtils.findField(BinaryJedis.class, "client", Client.class);
		ReflectionUtils.makeAccessible(CLIENT_FIELD);
		SEND_COMMAND = ReflectionUtils.findMethod(Connection.class, "sendCommand", new Class[] { Command.class,
				byte[][].class });
		ReflectionUtils.makeAccessible(SEND_COMMAND);
		GET_RESPONSE = ReflectionUtils.findMethod(Queable.class, "getResponse", Builder.class);
		ReflectionUtils.makeAccessible(GET_RESPONSE);
	}

	private final Jedis jedis;
	private final Client client;
	private final BinaryTransaction transaction;
	private final Pool<Jedis> pool;
	/** flag indicating whether the connection needs to be dropped or not */
	private boolean broken = false;
	private volatile JedisSubscription subscription;
	private volatile Pipeline pipeline;
	private final int dbIndex;
	private boolean convertPipelineResults=true;
	private List<FutureResult<Response<?>>> pipelinedResults = new ArrayList<FutureResult<Response<?>>>();

	private class JedisResult extends FutureResult<Response<?>> {
		public <T> JedisResult(Response<T> resultHolder, Converter<T,?> converter) {
			super(resultHolder, converter);
		}

		public <T> JedisResult(Response<T> resultHolder) {
			super(resultHolder);
		}

		@SuppressWarnings("unchecked")
		public Object get() {
			if(convertPipelineResults && converter != null) {
				return converter.convert(resultHolder.get());
			}
			return resultHolder.get();
		}
	}

	private class JedisStatusResult extends JedisResult {
		public JedisStatusResult(Response<?> resultHolder) {
			super(resultHolder);
		}
	}

	/**
	 * Constructs a new <code>JedisConnection</code> instance.
	 *
	 * @param jedis Jedis entity
	 */
	public JedisConnection(Jedis jedis) {
		this(jedis, null, 0);
	}

	/**
	 * 
	 * Constructs a new <code>JedisConnection</code> instance backed by a jedis pool.
	 *
	 * @param jedis
	 * @param pool can be null, if no pool is used
	 */
	public JedisConnection(Jedis jedis, Pool<Jedis> pool, int dbIndex) {
		this.jedis = jedis;
		// extract underlying connection for batch operations
		client = (Client) ReflectionUtils.getField(CLIENT_FIELD, jedis);
		transaction = new Transaction(client);

		this.pool = pool;

		this.dbIndex = dbIndex;

		// select the db
		// if this fail, do manual clean-up before propagating the exception
		// as we're inside the constructor
		if (dbIndex > 0) {
			try {
				select(dbIndex);
			} catch (DataAccessException ex) {
				close();
				throw ex;
			}
		}
	}

	protected DataAccessException convertJedisAccessException(Exception ex) {
		DataAccessException exception = JedisConverters.toDataAccessException(ex);
		if (exception instanceof RedisConnectionFailureException) {
			broken = true;
		}
		return exception;
	}

	public Object execute(String command, byte[]... args) {
		Assert.hasText(command, "a valid command needs to be specified");
		try {
			List<byte[]> mArgs = new ArrayList<byte[]>();
			if (!ObjectUtils.isEmpty(args)) {
				Collections.addAll(mArgs, args);
			}

			ReflectionUtils.invokeMethod(SEND_COMMAND, client,
					Command.valueOf(command.trim().toUpperCase()), mArgs.toArray(new byte[mArgs.size()][]));
			if (isQueueing() || isPipelined()) {
				Object target = (isPipelined() ? pipeline : transaction);
				@SuppressWarnings("unchecked")
				Response<Object> result = (Response<Object>)ReflectionUtils.invokeMethod(GET_RESPONSE, target, new Builder<Object>() {
					public Object build(Object data) {
						return data;
					}

					public String toString() {
						return "Object";
					}
				});
				if(isPipelined()) {
					pipeline(new JedisResult(result));
				}
				return null;
			}
			return client.getOne();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	public void close() throws DataAccessException {
		// return the connection to the pool
		try {
			if (pool != null) {
				if (!broken) {
					// reset the connection 
					if (dbIndex > 0) {
						select(0);
					}
					pool.returnResource(jedis);
					return;
				}
			}
		} catch (Exception ex) {
			// exceptions are handled below
		}

		if (pool != null && broken) {
			pool.returnBrokenResource(jedis);
			return;
		}

		// else close the connection normally (doing the try/catch dance)
		Exception exc = null;
		if (isQueueing()) {
			try {
				client.quit();
			} catch (Exception ex) {
				exc = ex;
			}
			try {
				client.disconnect();
			} catch (Exception ex) {
				exc = ex;
			}
			return;
		}
		try {
			jedis.quit();
		} catch (Exception ex) {
			exc = ex;
		}
		try {
			jedis.disconnect();
		} catch (Exception ex) {
			exc = ex;
		}
		if (exc != null)
			throw convertJedisAccessException(exc);
	}


	public Jedis getNativeConnection() {
		return jedis;
	}


	public boolean isClosed() {
		try {
			return !jedis.isConnected();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public boolean isQueueing() {
		return client.isInMulti();
	}


	public boolean isPipelined() {
		return (pipeline != null);
	}


	public void openPipeline() {
		if (pipeline == null) {
			pipeline = jedis.pipelined();
		}
	}

	public List<Object> closePipeline() {
		if (pipeline != null) {
			try {
				return convertPipelineResults();
			}finally {
				pipeline = null;
				pipelinedResults.clear();
			}
		}
		return Collections.emptyList();
	}

	private List<Object> convertPipelineResults() {
		List<Object> results = new ArrayList<Object>();
		pipeline.sync();
		Exception cause = null;
		for(FutureResult<Response<?>> result: pipelinedResults) {
			try {
				Object data = result.get();
				if(!convertPipelineResults || !(result instanceof JedisStatusResult)) {
					results.add(data);
				}
			}catch(JedisDataException e) {
				DataAccessException dataAccessException = convertJedisAccessException(e);
				if (cause == null) {
					cause = dataAccessException;
				}
				results.add(dataAccessException);
			}
		}
		if (cause != null) {
			throw new RedisPipelineException(cause, results);
		}
		return results;
	}

	private void pipeline(FutureResult<Response<?>> result) {
		pipelinedResults.add(result);
	}

	public List<byte[]> sort(byte[] key, SortParameters params) {

		SortingParams sortParams = JedisConverters.toSortingParams(params);

		try {
			if (isQueueing()) {
				if (sortParams != null) {
					transaction.sort(key, sortParams);
				}
				else {
					transaction.sort(key);
				}

				return null;
			}
			if (isPipelined()) {
				if (sortParams != null) {
					pipeline(new JedisResult(pipeline.sort(key, sortParams), JedisConverters.stringListToByteList()));
				}
				else {
					// Jedis pipeline gets ClassCastException trying to return Long instead of List<byte[]>
					// so no point trying to convert
					pipeline(new JedisResult(pipeline.sort(key)));
				}

				return null;
			}
			return (sortParams != null ? jedis.sort(key, sortParams) : jedis.sort(key));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long sort(byte[] key, SortParameters params, byte[] storeKey) {

		SortingParams sortParams = JedisConverters.toSortingParams(params);

		try {
			if (isQueueing()) {
				if (sortParams != null) {
					transaction.sort(key, sortParams, storeKey);
				}
				else {
					transaction.sort(key, storeKey);
				}

				return null;
			}
			if (isPipelined()) {
				if (sortParams != null) {
					pipeline(new JedisResult(pipeline.sort(key, sortParams, storeKey)));
				}
				else {
					pipeline(new JedisResult(pipeline.sort(key, storeKey)));
				}

				return null;
			}
			return (sortParams != null ? jedis.sort(key, sortParams, storeKey) : jedis.sort(key, storeKey));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long dbSize() {
		try {
			if (isQueueing()) {
				transaction.dbSize();
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.dbSize()));
				return null;
			}
			return jedis.dbSize();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}



	public void flushDb() {
		try {
			if (isQueueing()) {
				transaction.flushDB();
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.flushDB()));
				return;
			}
			jedis.flushDB();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void flushAll() {
		try {
			if (isQueueing()) {
				transaction.flushAll();
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.flushAll()));
				return;
			}
			jedis.flushAll();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void bgSave() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.bgsave()));
				return;
			}
			jedis.bgsave();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void bgWriteAof() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.bgrewriteaof()));
				return;
			}
			jedis.bgrewriteaof();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void save() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.save()));
				return;
			}
			jedis.save();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public List<String> getConfig(String param) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.configGet(param)));
				return null;
			}
			return jedis.configGet(param);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Properties info() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return JedisConverters.toProperties(jedis.info());
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Properties info(String section) {
		throw new UnsupportedOperationException();
	}


	public Long lastSave() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.lastsave()));
				return null;
			}
			return jedis.lastsave();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void setConfig(String param, String value) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.configSet(param, value)));
				return;
			}
			jedis.configSet(param, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}



	public void resetConfigStats() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.configResetStat()));
				return;
			}
			jedis.configResetStat();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void shutdown() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			jedis.shutdown();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] echo(byte[] message) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.echo(message),JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.echo(message);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public String ping() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return jedis.ping();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long del(byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.del(keys);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.del(keys)));
				return null;
			}
			return jedis.del(keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void discard() {
		try {
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.discard()));
				return;
			}
			transaction.discard();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public List<Object> exec() {
		try {
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.exec()));
				return null;
			}
			return transaction.exec();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean exists(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.exists(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.exists(key)));
				return null;
			}
			return jedis.exists(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean expire(byte[] key, long seconds) {
		try {
			if (isQueueing()) {
				transaction.expire(key, (int) seconds);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.expire(key, (int) seconds), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.expire(key, (int) seconds));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean expireAt(byte[] key, long unixTime) {
		try {
			if (isQueueing()) {
				transaction.expireAt(key, unixTime);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.expireAt(key, unixTime), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.expireAt(key, unixTime));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> keys(byte[] pattern) {
		try {
			if (isQueueing()) {
				transaction.keys(pattern);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.keys(pattern), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return (jedis.keys(pattern));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void multi() {
		if (isQueueing()) {
			return;
		}
		try {
			if (isPipelined()) {
				pipeline.multi();
				return;
			}
			jedis.multi();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean persist(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.persist(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.persist(key), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.persist(key));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean move(byte[] key, int dbIndex) {
		try {
			if (isQueueing()) {
				transaction.move(key, dbIndex);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.move(key, dbIndex), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.move(key, dbIndex));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] randomKey() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return jedis.randomBinaryKey();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void rename(byte[] oldName, byte[] newName) {
		try {
			if (isQueueing()) {
				transaction.rename(oldName, newName);
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.rename(oldName, newName)));
				return;
			}
			jedis.rename(oldName, newName);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean renameNX(byte[] oldName, byte[] newName) {
		try {
			if (isQueueing()) {
				transaction.renamenx(oldName, newName);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.renamenx(oldName, newName), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.renamenx(oldName, newName));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void select(int dbIndex) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			jedis.select(dbIndex);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long ttl(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.ttl(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.ttl(key)));
				return null;
			}
			return jedis.ttl(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	public Boolean pExpire(byte[] key, long millis) {
		throw new UnsupportedOperationException();
	}

	public Boolean pExpireAt(byte[] key, long unixTimeInMillis) {
		throw new UnsupportedOperationException();
	}

	public Long pTtl(byte[] key) {
		throw new UnsupportedOperationException();
	}

	public byte[] dump(byte[] key) {
		throw new UnsupportedOperationException();
	}

	public void restore(byte[] key, long ttlInMillis, byte[] serializedValue) {
		throw new UnsupportedOperationException();
	}

	public DataType type(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.type(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.type(key), JedisConverters.stringToDataType()));
				return null;
			}
			return JedisConverters.toDataType(jedis.type(key));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void unwatch() {
		try {
			jedis.unwatch();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void watch(byte[]... keys) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			for (byte[] key : keys) {
				if (isPipelined()) {
					pipeline(new JedisStatusResult(pipeline.watch(key)));
				}
				else {
					jedis.watch(key);
				}
			}
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	//
	// String commands
	//


	public byte[] get(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.get(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.get(key)));
				return null;
			}

			return jedis.get(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void set(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.set(key, value);
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.set(key, value)));
				return;
			}
			jedis.set(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}



	public byte[] getSet(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.getSet(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.getSet(key, value)));
				return null;
			}
			return jedis.getSet(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long append(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.append(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.append(key, value)));
				return null;
			}
			return jedis.append(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public List<byte[]> mGet(byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.mget(keys);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.mget(keys), JedisConverters.stringListToByteList()));
				return null;
			}
			return jedis.mget(keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void mSet(Map<byte[], byte[]> tuples) {
		try {
			if (isQueueing()) {
				transaction.mset(JedisConverters.toByteArrays(tuples));
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.mset(JedisConverters.toByteArrays(tuples))));
				return;
			}
			jedis.mset(JedisConverters.toByteArrays(tuples));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean mSetNX(Map<byte[], byte[]> tuples) {
		try {
			if (isQueueing()) {
				transaction.msetnx(JedisConverters.toByteArrays(tuples));
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.msetnx(JedisConverters.toByteArrays(tuples)), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.msetnx(JedisConverters.toByteArrays(tuples)));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void setEx(byte[] key, long time, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.setex(key, (int) time, value);
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.setex(key, (int) time, value)));
				return;
			}
			jedis.setex(key, (int) time, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean setNX(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.setnx(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.setnx(key, value), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.setnx(key, value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] getRange(byte[] key, long start, long end) {
		try {
			if (isQueueing()) {
				transaction.substr(key, (int) start, (int) end);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.substr(key, (int) start, (int) end), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.substr(key, (int) start, (int) end);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long decr(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.decr(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.decr(key)));
				return null;
			}
			return jedis.decr(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long decrBy(byte[] key, long value) {
		try {
			if (isQueueing()) {
				transaction.decrBy(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.decrBy(key, value)));
				return null;
			}
			return jedis.decrBy(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long incr(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.incr(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.incr(key)));
				return null;
			}
			return jedis.incr(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long incrBy(byte[] key, long value) {
		try {
			if (isQueueing()) {
				transaction.incrBy(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.incrBy(key, value)));
				return null;
			}
			return jedis.incrBy(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	public Double incrBy(byte[] key, double value) {
		throw new UnsupportedOperationException();
	}

	public Boolean getBit(byte[] key, long offset) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			// compatibility check for Jedis 2.0.0
			Object getBit = jedis.getbit(key, offset);
			// Jedis 2.0
			if (getBit instanceof Long) {
				return (((Long) getBit) == 0 ? Boolean.FALSE : Boolean.TRUE);
			}
			// Jedis 2.1
			return ((Boolean) getBit);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void setBit(byte[] key, long offset, boolean value) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			jedis.setbit(key, offset, JedisConverters.toBit(value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void setRange(byte[] key, byte[] value, long start) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			jedis.setrange(key, start, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long strLen(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.strlen(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.strlen(key)));
				return null;
			}
			return jedis.strlen(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long bitCount(byte[] key) {
		throw new UnsupportedOperationException();
	}


	public Long bitCount(byte[] key, long begin, long end) {
		throw new UnsupportedOperationException();
	}


	public Long bitOp(BitOperation op, byte[] destination, byte[]... keys) {
		throw new UnsupportedOperationException();
	}

	//
	// List commands
	//

	public Long lPush(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.lpush(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.lpush(key, value)));
				return null;
			}
			return jedis.lpush(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long rPush(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.rpush(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.rpush(key, value)));
				return null;
			}
			return jedis.rpush(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public List<byte[]> bLPop(int timeout, byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.blpop(bXPopArgs(timeout, keys));
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.blpop(bXPopArgs(timeout, keys)), JedisConverters.stringListToByteList()));
				return null;
			}
			return jedis.blpop(timeout, keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public List<byte[]> bRPop(int timeout, byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.brpop(bXPopArgs(timeout, keys));
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.brpop(bXPopArgs(timeout, keys)), JedisConverters.stringListToByteList()));
				return null;
			}
			return jedis.brpop(timeout, keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] lIndex(byte[] key, long index) {
		try {
			if (isQueueing()) {
				transaction.lindex(key, (int) index);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.lindex(key, (int) index), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.lindex(key, (int) index);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long lInsert(byte[] key, Position where, byte[] pivot, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.linsert(key, JedisConverters.toListPosition(where), pivot, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.linsert(key, JedisConverters.toListPosition(where), pivot, value)));
				return null;
			}
			return jedis.linsert(key, JedisConverters.toListPosition(where), pivot, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long lLen(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.llen(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.llen(key)));
				return null;
			}
			return jedis.llen(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] lPop(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.lpop(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.lpop(key), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.lpop(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public List<byte[]> lRange(byte[] key, long start, long end) {
		try {
			if (isQueueing()) {
				transaction.lrange(key, (int) start, (int) end);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.lrange(key, (int) start, (int) end), JedisConverters.stringListToByteList()));
				return null;
			}
			return jedis.lrange(key, (int) start, (int) end);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long lRem(byte[] key, long count, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.lrem(key, (int) count, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.lrem(key, (int) count, value)));
				return null;
			}
			return jedis.lrem(key, (int) count, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void lSet(byte[] key, long index, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.lset(key, (int) index, value);
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.lset(key, (int) index, value)));
				return;
			}
			jedis.lset(key, (int) index, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void lTrim(byte[] key, long start, long end) {
		try {
			if (isQueueing()) {
				transaction.ltrim(key, (int) start, (int) end);
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.ltrim(key, (int) start, (int) end)));
				return;
			}
			jedis.ltrim(key, (int) start, (int) end);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] rPop(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.rpop(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.rpop(key), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.rpop(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] rPopLPush(byte[] srcKey, byte[] dstKey) {
		try {
			if (isQueueing()) {
				transaction.rpoplpush(srcKey, dstKey);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.rpoplpush(srcKey, dstKey), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.rpoplpush(srcKey, dstKey);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] bRPopLPush(int timeout, byte[] srcKey, byte[] dstKey) {
		try {
			if (isQueueing()) {
				transaction.brpoplpush(srcKey, dstKey, timeout);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.brpoplpush(srcKey, dstKey, timeout), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.brpoplpush(srcKey, dstKey, timeout);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long lPushX(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.lpushx(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.lpushx(key, value)));
				return null;
			}
			return jedis.lpushx(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long rPushX(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.rpushx(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.rpushx(key, value)));
				return null;
			}
			return jedis.rpushx(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	//
	// Set commands
	//


	public Boolean sAdd(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.sadd(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.sadd(key, value), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.sadd(key, value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long sCard(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.scard(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.scard(key)));
				return null;
			}
			return jedis.scard(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> sDiff(byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.sdiff(keys);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.sdiff(keys), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.sdiff(keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long sDiffStore(byte[] destKey, byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.sdiffstore(destKey, keys);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.sdiffstore(destKey, keys)));
				return null;
			}
			return jedis.sdiffstore(destKey, keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> sInter(byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.sinter(keys);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.sinter(keys), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.sinter(keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long sInterStore(byte[] destKey, byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.sinterstore(destKey, keys);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.sinterstore(destKey, keys)));
				return null;
			}
			return jedis.sinterstore(destKey, keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean sIsMember(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.sismember(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.sismember(key, value)));
				return null;
			}
			return jedis.sismember(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> sMembers(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.smembers(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.smembers(key), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.smembers(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean sMove(byte[] srcKey, byte[] destKey, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.smove(srcKey, destKey, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.smove(srcKey, destKey, value), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.smove(srcKey, destKey, value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] sPop(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.spop(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.spop(key), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.spop(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] sRandMember(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.srandmember(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.srandmember(key), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.srandmember(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	public List<byte[]> sRandMember(byte[] key, long count) {
		throw new UnsupportedOperationException();
	}

	public Boolean sRem(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.srem(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.srem(key, value), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.srem(key, value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> sUnion(byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.sunion(keys);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.sunion(keys), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.sunion(keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long sUnionStore(byte[] destKey, byte[]... keys) {
		try {
			if (isQueueing()) {
				transaction.sunionstore(destKey, keys);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.sunionstore(destKey, keys)));
				return null;
			}
			return jedis.sunionstore(destKey, keys);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	//
	// ZSet commands
	//


	public Boolean zAdd(byte[] key, double score, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.zadd(key, score, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zadd(key, score, value), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.zadd(key, score, value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zCard(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.zcard(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zcard(key)));
				return null;
			}
			return jedis.zcard(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zCount(byte[] key, double min, double max) {
		try {
			if (isQueueing()) {
				transaction.zcount(key, min, max);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zcount(key, min, max)));
				return null;
			}
			return jedis.zcount(key, min, max);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Double zIncrBy(byte[] key, double increment, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.zincrby(key, increment, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zincrby(key, increment, value)));
				return null;
			}
			return jedis.zincrby(key, increment, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zInterStore(byte[] destKey, Aggregate aggregate, int[] weights, byte[]... sets) {
		try {
			ZParams zparams = new ZParams().weights(weights).aggregate(
					redis.clients.jedis.ZParams.Aggregate.valueOf(aggregate.name()));

			if (isQueueing()) {
				transaction.zinterstore(destKey, zparams, sets);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zinterstore(destKey, zparams, sets)));
				return null;
			}
			return jedis.zinterstore(destKey, zparams, sets);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zInterStore(byte[] destKey, byte[]... sets) {
		try {
			if (isQueueing()) {
				transaction.zinterstore(destKey, sets);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zinterstore(destKey, sets)));
				return null;
			}
			return jedis.zinterstore(destKey, sets);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> zRange(byte[] key, long start, long end) {
		try {
			if (isQueueing()) {
				transaction.zrange(key, (int) start, (int) end);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrange(key, (int) start, (int) end), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.zrange(key, (int) start, (int) end);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<Tuple> zRangeWithScores(byte[] key, long start, long end) {
		try {
			if (isQueueing()) {
				transaction.zrangeWithScores(key, (int) start, (int) end);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrangeWithScores(key, (int) start, (int) end), JedisConverters.tupleSetToTupleSet()));
				return null;
			}
			return JedisConverters.toTupleSet(jedis.zrangeWithScores(key, (int) start, (int) end));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> zRangeByScore(byte[] key, double min, double max) {
		try {
			if (isQueueing()) {
				transaction.zrangeByScore(key, min, max);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrangeByScore(key, min, max), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.zrangeByScore(key, min, max);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<Tuple> zRangeByScoreWithScores(byte[] key, double min, double max) {
		try {
			if (isQueueing()) {
				transaction.zrangeByScoreWithScores(key, min, max);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrangeByScoreWithScores(key, min, max), JedisConverters.tupleSetToTupleSet()));
				return null;
			}
			return JedisConverters.toTupleSet(jedis.zrangeByScoreWithScores(key, min, max));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<Tuple> zRevRangeWithScores(byte[] key, long start, long end) {
		try {
			if (isQueueing()) {
				transaction.zrevrangeWithScores(key, (int) start, (int) end);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrevrangeWithScores(key, (int) start, (int) end), JedisConverters.tupleSetToTupleSet()));
				return null;
			}
			return JedisConverters.toTupleSet(jedis.zrevrangeWithScores(key, (int) start, (int) end));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> zRangeByScore(byte[] key, double min, double max, long offset, long count) {
		try {
			if (isQueueing()) {
				transaction.zrangeByScore(key, min, max, (int) offset, (int) count);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrangeByScore(key, min, max, (int) offset, (int) count), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.zrangeByScore(key, min, max, (int) offset, (int) count);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<Tuple> zRangeByScoreWithScores(byte[] key, double min, double max, long offset, long count) {
		try {
			if (isQueueing()) {
				transaction.zrangeByScoreWithScores(key, min, max, (int) offset, (int) count);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrangeByScoreWithScores(key, min, max, (int) offset, (int) count), JedisConverters.tupleSetToTupleSet()));
				return null;
			}
			return JedisConverters.toTupleSet(jedis.zrangeByScoreWithScores(key, min, max, (int) offset, (int) count));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> zRevRangeByScore(byte[] key, double min, double max, long offset, long count) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return jedis.zrevrangeByScore(key, max, min, (int) offset, (int) count);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> zRevRangeByScore(byte[] key, double min, double max) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return jedis.zrevrangeByScore(key, max, min);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<Tuple> zRevRangeByScoreWithScores(byte[] key, double min, double max, long offset, long count) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return JedisConverters.toTupleSet(jedis.zrevrangeByScoreWithScores(key, max, min, (int) offset,
					(int) count));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<Tuple> zRevRangeByScoreWithScores(byte[] key, double min, double max) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return JedisConverters.toTupleSet(jedis.zrevrangeByScoreWithScores(key, max, min));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zRank(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.zrank(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrank(key, value)));
				return null;
			}
			return jedis.zrank(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean zRem(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.zrem(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrem(key, value), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.zrem(key, value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zRemRange(byte[] key, long start, long end) {
		try {
			if (isQueueing()) {
				transaction.zremrangeByRank(key, (int) start, (int) end);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zremrangeByRank(key, (int) start, (int) end)));
				return null;
			}
			return jedis.zremrangeByRank(key, (int) start, (int) end);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zRemRangeByScore(byte[] key, double min, double max) {
		try {
			if (isQueueing()) {
				transaction.zremrangeByScore(key, min, max);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zremrangeByScore(key, min, max)));
				return null;
			}
			return jedis.zremrangeByScore(key, min, max);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Set<byte[]> zRevRange(byte[] key, long start, long end) {
		try {
			if (isQueueing()) {
				transaction.zrevrange(key, (int) start, (int) end);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrevrange(key, (int) start, (int) end), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.zrevrange(key, (int) start, (int) end);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zRevRank(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.zrevrank(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zrevrank(key, value)));
				return null;
			}
			return jedis.zrevrank(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Double zScore(byte[] key, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.zscore(key, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zscore(key, value)));
				return null;
			}
			return jedis.zscore(key, value);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zUnionStore(byte[] destKey, Aggregate aggregate, int[] weights, byte[]... sets) {
		try {
			ZParams zparams = new ZParams().weights(weights).aggregate(
					redis.clients.jedis.ZParams.Aggregate.valueOf(aggregate.name()));

			if (isQueueing()) {
				transaction.zunionstore(destKey, zparams, sets);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zunionstore(destKey, zparams, sets)));
				return null;
			}
			return jedis.zunionstore(destKey, zparams, sets);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long zUnionStore(byte[] destKey, byte[]... sets) {
		try {
			if (isQueueing()) {
				transaction.zunionstore(destKey, sets);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.zunionstore(destKey, sets)));
				return null;
			}
			return jedis.zunionstore(destKey, sets);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	//
	// Hash commands
	//


	public Boolean hSet(byte[] key, byte[] field, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.hset(key, field, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hset(key, field, value), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.hset(key, field, value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean hSetNX(byte[] key, byte[] field, byte[] value) {
		try {
			if (isQueueing()) {
				transaction.hsetnx(key, field, value);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hsetnx(key, field, value), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.hsetnx(key, field, value));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean hDel(byte[] key, byte[] field) {
		try {
			if (isQueueing()) {
				transaction.hdel(key, field);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hdel(key, field), JedisConverters.longToBoolean()));
				return null;
			}
			return JedisConverters.toBoolean(jedis.hdel(key, field));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Boolean hExists(byte[] key, byte[] field) {
		try {
			if (isQueueing()) {
				transaction.hexists(key, field);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hexists(key, field)));
				return null;
			}
			return jedis.hexists(key, field);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public byte[] hGet(byte[] key, byte[] field) {
		try {
			if (isQueueing()) {
				transaction.hget(key, field);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hget(key, field), JedisConverters.stringToBytes()));
				return null;
			}
			return jedis.hget(key, field);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Map<byte[], byte[]> hGetAll(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.hgetAll(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hgetAll(key), JedisConverters.stringMapToByteMap()));
				return null;
			}
			return jedis.hgetAll(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long hIncrBy(byte[] key, byte[] field, long delta) {
		try {
			if (isQueueing()) {
				transaction.hincrBy(key, field, delta);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hincrBy(key, field, delta)));
				return null;
			}
			return jedis.hincrBy(key, field, delta);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	public Double hIncrBy(byte[] key, byte[] field, double delta) {
		throw new UnsupportedOperationException();
	}

	public Set<byte[]> hKeys(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.hkeys(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hkeys(key), JedisConverters.stringSetToByteSet()));
				return null;
			}
			return jedis.hkeys(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Long hLen(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.hlen(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hlen(key)));
				return null;
			}
			return jedis.hlen(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public List<byte[]> hMGet(byte[] key, byte[]... fields) {
		try {
			if (isQueueing()) {
				transaction.hmget(key, fields);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hmget(key, fields), JedisConverters.stringListToByteList()));
				return null;
			}
			return jedis.hmget(key, fields);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void hMSet(byte[] key, Map<byte[], byte[]> tuple) {
		try {
			if (isQueueing()) {
				transaction.hmset(key, tuple);
				return;
			}
			if (isPipelined()) {
				pipeline(new JedisStatusResult(pipeline.hmset(key, tuple)));
				return;
			}
			jedis.hmset(key, tuple);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public List<byte[]> hVals(byte[] key) {
		try {
			if (isQueueing()) {
				transaction.hvals(key);
				return null;
			}
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.hvals(key), JedisConverters.stringListToByteList()));
				return null;
			}
			return jedis.hvals(key);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	//
	// Pub/Sub functionality
	//

	public Long publish(byte[] channel, byte[] message) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(new JedisResult(pipeline.publish(channel, message)));
				return null;
			}
			return jedis.publish(channel, message);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public Subscription getSubscription() {
		return subscription;
	}


	public boolean isSubscribed() {
		return (subscription != null && subscription.isAlive());
	}


	public void pSubscribe(MessageListener listener, byte[]... patterns) {
		if (isSubscribed()) {
			throw new RedisSubscribedConnectionException(
					"Connection already subscribed; use the connection Subscription to cancel or add new channels");
		}
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}

		try {
			BinaryJedisPubSub jedisPubSub = new JedisMessageListener(listener);

			subscription = new JedisSubscription(listener, jedisPubSub, null, patterns);
			jedis.psubscribe(jedisPubSub, patterns);

		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}


	public void subscribe(MessageListener listener, byte[]... channels) {
		if (isSubscribed()) {
			throw new RedisSubscribedConnectionException(
					"Connection already subscribed; use the connection Subscription to cancel or add new channels");
		}

		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}

		try {
			BinaryJedisPubSub jedisPubSub = new JedisMessageListener(listener);

			subscription = new JedisSubscription(listener, jedisPubSub, channels, null);
			jedis.subscribe(jedisPubSub, channels);

		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	//
	// Scripting commands
	//

	public void scriptFlush() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			jedis.scriptFlush();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	public void scriptKill() {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			jedis.scriptKill();
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	public String scriptLoad(byte[] script) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return JedisConverters.toString(jedis.scriptLoad(script));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	public List<Boolean> scriptExists(String... scriptSha1) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return jedis.scriptExists(scriptSha1);
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	public <T> T eval(byte[] script, ReturnType returnType, int numKeys, byte[]... keysAndArgs) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return (T) new JedisScriptReturnConverter(returnType).convert(
					jedis.eval(script, JedisConverters.toBytes(numKeys), keysAndArgs));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	public <T> T evalSha(String scriptSha1, ReturnType returnType, int numKeys, byte[]... keysAndArgs) {
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		if (isPipelined()) {
			throw new UnsupportedOperationException();
		}
		try {
			return (T) new JedisScriptReturnConverter(returnType).convert(
					jedis.evalsha(scriptSha1, numKeys, JedisConverters.toStrings(keysAndArgs)));
		} catch (Exception ex) {
			throw convertJedisAccessException(ex);
		}
	}

	/**
	 * Specifies if pipelined results should be converted to the expected data
	 * type. If false, results of {@link #closePipeline()} will be of the
	 * type returned by the Jedis driver
	 *
	 * @param convertPipelineResults Whether or not to convert pipeline results
	 */
	public void setConvertPipelineResults(boolean convertPipelineResults) {
		this.convertPipelineResults = convertPipelineResults;
	}

	private byte[][] bXPopArgs(int timeout, byte[]... keys) {
		final List<byte[]> args = new ArrayList<byte[]>();
		for (final byte[] arg : keys) {
			args.add(arg);
		}
		args.add(Protocol.toByteArray(timeout));
		return args.toArray(new byte[args.size()][]);
	}
}
