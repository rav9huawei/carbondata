/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.presto;

import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.Decimals;
import com.facebook.presto.spi.type.TimestampType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;

import com.google.common.base.Strings;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.apache.carbondata.common.CarbonIterator;
import org.apache.carbondata.hadoop.readsupport.CarbonReadSupport;


import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;


import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.Decimals.isShortDecimal;
import static com.facebook.presto.spi.type.Decimals.rescale;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.Slices.utf8Slice;

public class CarbondataRecordCursor implements RecordCursor {

  private static final Logger log = Logger.get(CarbondataRecordCursor.class);
  private final List<CarbondataColumnHandle> columnHandles;

  private List<String> fields;
  private CarbondataSplit split;
  private CarbonIterator<Object[]> rowCursor;
  private CarbonReadSupport<Object[]> readSupport;

  private long totalBytes;
  private long nanoStart;
  private long nanoEnd;

  public CarbondataRecordCursor(CarbonReadSupport<Object[]> readSupport,
      CarbonIterator<Object[]> carbonIterator, List<CarbondataColumnHandle> columnHandles,
      CarbondataSplit split) {
    this.rowCursor = carbonIterator;
    this.columnHandles = columnHandles;
    this.readSupport = readSupport;
    this.totalBytes = 0;
  }

  @Override public long getTotalBytes() {
    return totalBytes;
  }

  @Override public long getCompletedBytes() {
    return totalBytes;
  }

  @Override public long getReadTimeNanos() {
    return nanoStart > 0L ? (nanoEnd == 0 ? System.nanoTime() : nanoEnd) - nanoStart : 0L;
  }

  @Override public Type getType(int field) {

    checkArgument(field < columnHandles.size(), "Invalid field index");
    return columnHandles.get(field).getColumnType();
  }

  /**
   * get next Row/Page
   */
  @Override public boolean advanceNextPosition() {

    if (nanoStart == 0) {
      nanoStart = System.nanoTime();
    }

    if (rowCursor.hasNext()) {
      Object[] columns = readSupport.readRow(rowCursor.next());
      fields = new ArrayList<String>();
      if(columns != null && columns.length > 0)
      {
        for(Object value : columns){
          if(value != null )
          {
            fields.add(value.toString());
          } else {
            fields.add(null);
          }
        }
      }
      totalBytes += columns.length;
      return true;
    }
    return false;
  }

  @Override public boolean getBoolean(int field) {
    checkFieldType(field, BOOLEAN);
    return Boolean.parseBoolean(getFieldValue(field));
  }

  @Override public long getLong(int field) {
    String timeStr = getFieldValue(field);
    Type actual = getType(field);
    if(actual instanceof TimestampType){
      return new Timestamp(Long.parseLong(timeStr)).getTime()/1000;
    }
    //suppose the
    return Math.round(Double.parseDouble(getFieldValue(field)));
  }

  @Override public double getDouble(int field) {
    checkFieldType(field, DOUBLE);
    return Double.parseDouble(getFieldValue(field));
  }

  @Override public Slice getSlice(int field) {
    Type decimalType = getType(field);
    if (decimalType instanceof DecimalType) {
      DecimalType actual = (DecimalType) decimalType;
      CarbondataColumnHandle carbondataColumnHandle = columnHandles.get(field);
      if(carbondataColumnHandle.getPrecision() > 0 ) {
        checkFieldType(field, DecimalType.createDecimalType(carbondataColumnHandle.getPrecision(), carbondataColumnHandle.getScale()));
      } else {
        checkFieldType(field, DecimalType.createDecimalType());
      }
      String fieldValue = getFieldValue(field);
      BigDecimal bigDecimalValue = new BigDecimal(fieldValue);
      if (isShortDecimal(decimalType)) {
        return utf8Slice(Decimals.toString(bigDecimalValue.longValue(), actual.getScale()));
      } else {
        if (bigDecimalValue.scale() > actual.getScale()) {
          BigInteger unscaledDecimal =
              rescale(bigDecimalValue.unscaledValue(), bigDecimalValue.scale(),
                  bigDecimalValue.scale());
          Slice decimalSlice = Decimals.encodeUnscaledValue(unscaledDecimal);
          return utf8Slice(Decimals.toString(decimalSlice, actual.getScale()));
          //return decimalSlice;
        } else {
          BigInteger unscaledDecimal =
              rescale(bigDecimalValue.unscaledValue(), bigDecimalValue.scale(), actual.getScale());
          Slice decimalSlice = Decimals.encodeUnscaledValue(unscaledDecimal);
          return utf8Slice(Decimals.toString(decimalSlice, actual.getScale()));
          //return decimalSlice;

        }

      }
    } else {
      checkFieldType(field, VARCHAR);
      return utf8Slice(getFieldValue(field));
    }
  }

  @Override public Object getObject(int field) {
    return null;
  }

  @Override public boolean isNull(int field) {
    checkArgument(field < columnHandles.size(), "Invalid field index");
    return Strings.isNullOrEmpty(getFieldValue(field));
  }

  String getFieldValue(int field) {
    checkState(fields != null, "Cursor has not been advanced yet");
    return fields.get(field);
  }

  private void checkFieldType(int field, Type expected) {
    Type actual = getType(field);
    checkArgument(actual.equals(expected), "Expected field %s to be type %s but is %s", field,
        expected, actual);
  }

  @Override public void close() {
    nanoEnd = System.nanoTime();

    //todo  delete cache from readSupport
  }
}
