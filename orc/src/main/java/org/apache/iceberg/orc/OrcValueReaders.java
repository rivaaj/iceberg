/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.orc;

import com.google.common.collect.Lists;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.types.Types;
import org.apache.orc.storage.ql.exec.vector.BytesColumnVector;
import org.apache.orc.storage.ql.exec.vector.ColumnVector;
import org.apache.orc.storage.ql.exec.vector.DoubleColumnVector;
import org.apache.orc.storage.ql.exec.vector.LongColumnVector;
import org.apache.orc.storage.ql.exec.vector.StructColumnVector;
import org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch;


public class OrcValueReaders {
  private OrcValueReaders() {
  }

  public static OrcValReader<?> booleans() {
    return BooleanReader.INSTANCE;
  }

  public static OrcValReader<?> shorts() {
    return ShortReader.INSTANCE;
  }

  public static OrcValReader<?> ints() {
    return IntegerReader.INSTANCE;
  }

  public static OrcValReader<?> longs() {
    return LongReader.INSTANCE;
  }

  public static OrcValReader<?> floats() {
    return FloatReader.INSTANCE;
  }

  public static OrcValReader<?> doubles() {
    return DoubleReader.INSTANCE;
  }

  public static OrcValReader<?> bytes() {
    return BytesReader.INSTANCE;
  }

  public static OrcValReader<?> byteReader() {
    return ByteReader.INSTANCE;
  }

  private static class BooleanReader implements OrcValReader<Boolean> {
    static final OrcValReader<?> INSTANCE = new BooleanReader();

    private BooleanReader() {
    }

    @Override
    public Boolean nonNullRead(ColumnVector vector, int row) {
      return ((LongColumnVector) vector).vector[row] != 0;
    }
  }

  private static class ShortReader implements OrcValReader<Short> {
    static final OrcValReader<?> INSTANCE = new ShortReader();

    private ShortReader() {
    }

    @Override
    public Short nonNullRead(ColumnVector vector, int row) {
      return (short) ((LongColumnVector) vector).vector[row];
    }
  }

  private static class IntegerReader implements OrcValReader<Integer> {
    static final OrcValReader<?> INSTANCE = new IntegerReader();

    private IntegerReader() {
    }

    @Override
    public Integer nonNullRead(ColumnVector vector, int row) {
      return (int) ((LongColumnVector) vector).vector[row];
    }
  }

  private static class LongReader implements OrcValReader<Long> {
    static final OrcValReader<?> INSTANCE = new LongReader();

    private LongReader() {
    }

    @Override
    public Long nonNullRead(ColumnVector vector, int row) {
      return ((LongColumnVector) vector).vector[row];
    }
  }

  private static class FloatReader implements OrcValReader<Float> {
    private static final FloatReader INSTANCE = new FloatReader();

    private FloatReader() {
    }

    @Override
    public Float nonNullRead(ColumnVector vector, int row) {
      return (float) ((DoubleColumnVector) vector).vector[row];
    }
  }

  private static class DoubleReader implements OrcValReader<Double> {
    private static final DoubleReader INSTANCE = new DoubleReader();

    private DoubleReader() {
    }

    @Override
    public Double nonNullRead(ColumnVector vector, int row) {
      return ((DoubleColumnVector) vector).vector[row];
    }
  }

  private static class ByteReader implements OrcValReader<Byte> {
    private static final ByteReader INSTANCE = new ByteReader();

    private ByteReader() {
    }

    @Override
    public Byte nonNullRead(ColumnVector vector, int row) {
      return (byte) ((LongColumnVector) vector).vector[row];
    }
  }

  private static class BytesReader implements OrcValReader<byte[]> {
    private static final BytesReader INSTANCE = new BytesReader();

    private BytesReader() {
    }

    @Override
    public byte[] nonNullRead(ColumnVector vector, int row) {
      BytesColumnVector bytesVector = (BytesColumnVector) vector;

      return Arrays.copyOfRange(
          bytesVector.vector[row], bytesVector.start[row], bytesVector.start[row] + bytesVector.length[row]);
    }
  }

  public abstract static class StructReader<T> implements OrcValReader<T> {
    private final OrcValReader<?>[] readers;
    private final int[] positions;
    private final Object[] constants;

    protected StructReader(List<OrcValReader<?>> readers) {
      this.readers = readers.toArray(new OrcValReader[0]);
      this.positions = new int[0];
      this.constants = new Object[0];
    }

    protected StructReader(List<OrcValReader<?>> readers, Types.StructType struct, Map<Integer, ?> idToConstant) {
      this.readers = readers.toArray(new OrcValReader[0]);
      List<Types.NestedField> fields = struct.fields();
      List<Integer> positionList = Lists.newArrayListWithCapacity(fields.size());
      List<Object> constantList = Lists.newArrayListWithCapacity(fields.size());
      for (int pos = 0; pos < fields.size(); pos += 1) {
        Types.NestedField field = fields.get(pos);
        Object constant = idToConstant.get(field.fieldId());
        if (constant != null) {
          positionList.add(pos);
          constantList.add(idToConstant.get(field.fieldId()));
        }
      }

      this.positions = positionList.stream().mapToInt(Integer::intValue).toArray();
      this.constants = constantList.toArray();
    }

    protected abstract T create();

    protected abstract T reuseOrCreate();

    protected abstract void set(T struct, int pos, Object value);

    public OrcValReader<?> reader(int pos) {
      return readers[pos];
    }

    @Override
    public T nonNullRead(ColumnVector vector, int row) {
      StructColumnVector structVector = (StructColumnVector) vector;
      return readInternal(create(), structVector.fields, row);
    }

    public T read(VectorizedRowBatch batch, int row) {
      return readInternal(reuseOrCreate(), batch.cols, row);
    }

    private T readInternal(T struct, ColumnVector[] columnVectors, int row) {
      for (int c = 0; c < readers.length; ++c) {
        set(struct, c, reader(c).read(columnVectors[c], row));
      }

      for (int i = 0; i < positions.length; i += 1) {
        set(struct, positions[i], constants[i]);
      }

      return struct;
    }
  }
}