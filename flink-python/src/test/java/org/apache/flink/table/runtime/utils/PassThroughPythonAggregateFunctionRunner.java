/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.utils;

import org.apache.flink.core.memory.ByteArrayInputStreamWithPos;
import org.apache.flink.core.memory.ByteArrayOutputStreamWithPos;
import org.apache.flink.fnexecution.v1.FlinkFnApi;
import org.apache.flink.python.PythonConfig;
import org.apache.flink.python.env.PythonEnvironmentManager;
import org.apache.flink.python.metric.FlinkMetricContainer;
import org.apache.flink.table.runtime.arrow.serializers.RowDataArrowSerializer;
import org.apache.flink.table.runtime.runners.python.beam.BeamTableStatelessPythonFunctionRunner;
import org.apache.flink.table.types.logical.RowType;

import org.apache.beam.runners.fnexecution.control.JobBundleFactory;
import org.apache.beam.vendor.grpc.v1p26p0.com.google.protobuf.Struct;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * A {@link PassThroughPythonAggregateFunctionRunner} runner that just return the first input element
 * with the same key as the execution results.
 */
public class PassThroughPythonAggregateFunctionRunner extends BeamTableStatelessPythonFunctionRunner {

	private final List<byte[]> buffer;

	private final RowDataArrowSerializer arrowSerializer;

	/**
	 * Reusable InputStream used to holding the execution results to be deserialized.
	 */
	private transient ByteArrayInputStreamWithPos bais;

	/**
	 * Reusable OutputStream used to holding the serialized input elements.
	 */
	private transient ByteArrayOutputStreamWithPos baos;

	public PassThroughPythonAggregateFunctionRunner(
		String taskName,
		PythonEnvironmentManager environmentManager,
		RowType inputType,
		RowType outputType,
		String functionUrn,
		FlinkFnApi.UserDefinedFunctions userDefinedFunctions,
		String coderUrn,
		Map<String, String> jobOptions,
		FlinkMetricContainer flinkMetricContainer) {
		super(taskName, environmentManager, inputType, outputType, functionUrn, userDefinedFunctions,
			coderUrn, jobOptions, flinkMetricContainer);
		this.buffer = new LinkedList<>();
		arrowSerializer = new RowDataArrowSerializer(inputType, outputType);
	}

	@Override
	public void open(PythonConfig config) throws Exception {
		super.open(config);
		bais = new ByteArrayInputStreamWithPos();
		baos = new ByteArrayOutputStreamWithPos();
		arrowSerializer.open(bais, baos);
	}

	@Override
	protected void startBundle() {
		super.startBundle();
		this.mainInputReceiver = input -> {
			byte[] data = input.getValue();
			bais.setBuffer(data, 0, data.length);
			arrowSerializer.load();
			arrowSerializer.write(arrowSerializer.read(0));
			arrowSerializer.finishCurrentBatch();
			buffer.add(baos.toByteArray());
			baos.reset();
		};
	}

	@Override
	public void flush() throws Exception {
		super.flush();
		resultBuffer.addAll(buffer);
		buffer.clear();
	}

	@Override
	public JobBundleFactory createJobBundleFactory(Struct pipelineOptions) {
		return PythonTestUtils.createMockJobBundleFactory();
	}
}
