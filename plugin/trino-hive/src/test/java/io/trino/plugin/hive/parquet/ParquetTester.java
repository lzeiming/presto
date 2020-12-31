/*
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
package io.trino.plugin.hive.parquet;

import com.google.common.base.Joiner;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import io.trino.parquet.writer.ParquetSchemaConverter;
import io.trino.parquet.writer.ParquetWriter;
import io.trino.parquet.writer.ParquetWriterOptions;
import io.trino.plugin.hive.HiveConfig;
import io.trino.plugin.hive.HiveSessionProperties;
import io.trino.plugin.hive.HiveStorageFormat;
import io.trino.plugin.hive.benchmark.FileFormat;
import io.trino.plugin.hive.orc.OrcReaderConfig;
import io.trino.plugin.hive.orc.OrcWriterConfig;
import io.trino.plugin.hive.parquet.write.MapKeyValuesSchemaConverter;
import io.trino.plugin.hive.parquet.write.SingleLevelArrayMapKeyValuesSchemaConverter;
import io.trino.plugin.hive.parquet.write.SingleLevelArraySchemaConverter;
import io.trino.plugin.hive.parquet.write.TestMapredParquetOutputFormat;
import io.trino.spi.Page;
import io.trino.spi.PageBuilder;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.RecordPageSource;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.SqlDate;
import io.trino.spi.type.SqlDecimal;
import io.trino.spi.type.SqlTimestamp;
import io.trino.spi.type.SqlVarbinary;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import io.trino.testing.TestingConnectorSession;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.FileSinkOperator.RecordWriter;
import org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.SettableStructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.parquet.column.ParquetProperties.WriterVersion;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.MessageType;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.Functions.constant;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.transform;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.hive.AbstractTestHiveFileFormats.getFieldFromCursor;
import static io.trino.plugin.hive.HiveSessionProperties.getParquetMaxReadBlockSize;
import static io.trino.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.trino.plugin.hive.HiveTestUtils.getHiveSession;
import static io.trino.plugin.hive.util.HiveUtil.isArrayType;
import static io.trino.plugin.hive.util.HiveUtil.isMapType;
import static io.trino.plugin.hive.util.HiveUtil.isRowType;
import static io.trino.plugin.hive.util.HiveUtil.isStructuralType;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.Chars.truncateToLengthAndTrimSpaces;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.Varchars.truncateToLength;
import static java.lang.Math.toIntExact;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.Collections.singletonList;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardStructObjectInspector;
import static org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils.getTypeInfosFromTypeString;
import static org.apache.parquet.column.ParquetProperties.WriterVersion.PARQUET_1_0;
import static org.apache.parquet.hadoop.ParquetOutputFormat.COMPRESSION;
import static org.apache.parquet.hadoop.ParquetOutputFormat.ENABLE_DICTIONARY;
import static org.apache.parquet.hadoop.ParquetOutputFormat.WRITER_VERSION;
import static org.apache.parquet.hadoop.metadata.CompressionCodecName.GZIP;
import static org.apache.parquet.hadoop.metadata.CompressionCodecName.LZ4;
import static org.apache.parquet.hadoop.metadata.CompressionCodecName.LZO;
import static org.apache.parquet.hadoop.metadata.CompressionCodecName.SNAPPY;
import static org.apache.parquet.hadoop.metadata.CompressionCodecName.UNCOMPRESSED;
import static org.apache.parquet.hadoop.metadata.CompressionCodecName.ZSTD;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ParquetTester
{
    private static final int MAX_PRECISION_INT64 = toIntExact(maxPrecision(8));

    private static final boolean OPTIMIZED = true;
    private static final ConnectorSession SESSION = getHiveSession(createHiveConfig(false));
    private static final ConnectorSession SESSION_USE_NAME = getHiveSession(createHiveConfig(true));
    private static final List<String> TEST_COLUMN = singletonList("test");

    private Set<CompressionCodecName> compressions = ImmutableSet.of();

    private Set<CompressionCodecName> writerCompressions = ImmutableSet.of();

    private Set<WriterVersion> versions = ImmutableSet.of();

    private Set<ConnectorSession> sessions = ImmutableSet.of();

    public static ParquetTester quickParquetTester()
    {
        ParquetTester parquetTester = new ParquetTester();
        parquetTester.compressions = ImmutableSet.of(GZIP);
        parquetTester.writerCompressions = ImmutableSet.of(GZIP);
        parquetTester.versions = ImmutableSet.of(PARQUET_1_0);
        parquetTester.sessions = ImmutableSet.of(SESSION);
        return parquetTester;
    }

    public static ParquetTester fullParquetTester()
    {
        ParquetTester parquetTester = new ParquetTester();
        parquetTester.compressions = ImmutableSet.of(GZIP, UNCOMPRESSED, SNAPPY, LZO, LZ4, ZSTD);
        parquetTester.writerCompressions = ImmutableSet.of(GZIP, UNCOMPRESSED, SNAPPY, ZSTD);
        parquetTester.versions = ImmutableSet.copyOf(WriterVersion.values());
        parquetTester.sessions = ImmutableSet.of(SESSION, SESSION_USE_NAME);
        return parquetTester;
    }

    public void testRoundTrip(PrimitiveObjectInspector columnObjectInspector, Iterable<?> writeValues, Type parameterType)
            throws Exception
    {
        testRoundTrip(columnObjectInspector, writeValues, writeValues, parameterType);
    }

    public <W, R> void testRoundTrip(PrimitiveObjectInspector columnObjectInspector, Iterable<W> writeValues, Function<W, R> readTransform, Type parameterType)
            throws Exception
    {
        testRoundTrip(columnObjectInspector, writeValues, transform(writeValues, readTransform::apply), parameterType);
    }

    public void testSingleLevelArraySchemaRoundTrip(ObjectInspector objectInspector, Iterable<?> writeValues, Iterable<?> readValues, Type type)
            throws Exception
    {
        List<TypeInfo> typeInfos = getTypeInfosFromTypeString(objectInspector.getTypeName());
        MessageType schema = SingleLevelArraySchemaConverter.convert(TEST_COLUMN, typeInfos);
        testSingleLevelArrayRoundTrip(objectInspector, writeValues, readValues, type, Optional.of(schema));
        if (objectInspector.getTypeName().contains("map<")) {
            schema = SingleLevelArrayMapKeyValuesSchemaConverter.convert(TEST_COLUMN, typeInfos);
            testSingleLevelArrayRoundTrip(objectInspector, writeValues, readValues, type, Optional.of(schema));
        }
    }

    public void testRoundTrip(ObjectInspector objectInspector, Iterable<?> writeValues, Iterable<?> readValues, Type type)
            throws Exception
    {
        // just the values
        testRoundTripType(singletonList(objectInspector), new Iterable<?>[] {writeValues},
                new Iterable<?>[] {readValues}, TEST_COLUMN, singletonList(type), Optional.empty(), false);

        // all nulls
        assertRoundTrip(singletonList(objectInspector), new Iterable<?>[] {transform(writeValues, constant(null))},
                new Iterable<?>[] {transform(writeValues, constant(null))}, TEST_COLUMN, singletonList(type), Optional.empty());
        if (objectInspector.getTypeName().contains("map<")) {
            List<TypeInfo> typeInfos = getTypeInfosFromTypeString(objectInspector.getTypeName());
            MessageType schema = MapKeyValuesSchemaConverter.convert(TEST_COLUMN, typeInfos);
            // just the values
            testRoundTripType(singletonList(objectInspector), new Iterable<?>[] {writeValues}, new Iterable<?>[] {
                    readValues}, TEST_COLUMN, singletonList(type), Optional.of(schema), false);

            // all nulls
            assertRoundTrip(singletonList(objectInspector), new Iterable<?>[] {transform(writeValues, constant(null))},
                    new Iterable<?>[] {transform(writeValues, constant(null))}, TEST_COLUMN, singletonList(type), Optional.of(schema));
        }
    }

    public void testRoundTrip(ObjectInspector objectInspector, Iterable<?> writeValues, Iterable<?> readValues, Type type, Optional<MessageType> parquetSchema)
            throws Exception
    {
        testRoundTrip(singletonList(objectInspector), new Iterable<?>[] {writeValues}, new Iterable<?>[] {readValues}, TEST_COLUMN, singletonList(type), parquetSchema, false);
    }

    public void testSingleLevelArrayRoundTrip(ObjectInspector objectInspector, Iterable<?> writeValues, Iterable<?> readValues, Type type, Optional<MessageType> parquetSchema)
            throws Exception
    {
        testRoundTrip(singletonList(objectInspector), new Iterable<?>[] {writeValues}, new Iterable<?>[] {readValues}, TEST_COLUMN, singletonList(type), parquetSchema, true);
    }

    public void testRoundTrip(ObjectInspector objectInspector, Iterable<?> writeValues, Iterable<?> readValues, String columnName, Type type, Optional<MessageType> parquetSchema)
            throws Exception
    {
        testRoundTrip(
                singletonList(objectInspector),
                new Iterable<?>[] {writeValues},
                new Iterable<?>[] {readValues},
                singletonList(columnName),
                singletonList(type),
                parquetSchema,
                false);
    }

    public void testSingleLevelArrayRoundTrip(ObjectInspector objectInspector, Iterable<?> writeValues, Iterable<?> readValues, String columnName, Type type, Optional<MessageType> parquetSchema)
            throws Exception
    {
        testRoundTrip(
                singletonList(objectInspector),
                new Iterable<?>[] {writeValues},
                new Iterable<?>[] {readValues},
                singletonList(columnName),
                singletonList(type),
                parquetSchema,
                true);
    }

    public void testRoundTrip(List<ObjectInspector> objectInspectors, Iterable<?>[] writeValues, Iterable<?>[] readValues, List<String> columnNames, List<Type> columnTypes, Optional<MessageType> parquetSchema, boolean singleLevelArray)
            throws Exception
    {
        // just the values
        testRoundTripType(objectInspectors, writeValues, readValues, columnNames, columnTypes, parquetSchema, singleLevelArray);

        // all nulls
        assertRoundTrip(objectInspectors, transformToNulls(writeValues), transformToNulls(readValues), columnNames, columnTypes, parquetSchema, singleLevelArray);
    }

    private void testRoundTripType(
            List<ObjectInspector> objectInspectors,
            Iterable<?>[] writeValues,
            Iterable<?>[] readValues,
            List<String> columnNames,
            List<Type> columnTypes,
            Optional<MessageType> parquetSchema,
            boolean singleLevelArray)
            throws Exception
    {
        // forward order
        assertRoundTrip(objectInspectors, writeValues, readValues, columnNames, columnTypes, parquetSchema, singleLevelArray);

        // reverse order
        assertRoundTrip(objectInspectors, reverse(writeValues), reverse(readValues), columnNames, columnTypes, parquetSchema, singleLevelArray);

        // forward order with nulls
        assertRoundTrip(objectInspectors, insertNullEvery(5, writeValues), insertNullEvery(5, readValues), columnNames, columnTypes, parquetSchema, singleLevelArray);

        // reverse order with nulls
        assertRoundTrip(objectInspectors, insertNullEvery(5, reverse(writeValues)), insertNullEvery(5, reverse(readValues)), columnNames, columnTypes, parquetSchema, singleLevelArray);
    }

    void assertRoundTrip(
            List<ObjectInspector> objectInspectors,
            Iterable<?>[] writeValues,
            Iterable<?>[] readValues,
            List<String> columnNames,
            List<Type> columnTypes,
            Optional<MessageType> parquetSchema)
            throws Exception
    {
        assertRoundTrip(objectInspectors, writeValues, readValues, columnNames, columnTypes, parquetSchema, false);
    }

    void assertRoundTrip(
            List<ObjectInspector> objectInspectors,
            Iterable<?>[] writeValues,
            Iterable<?>[] readValues,
            List<String> columnNames,
            List<Type> columnTypes,
            Optional<MessageType> parquetSchema,
            boolean singleLevelArray)
            throws Exception
    {
        for (WriterVersion version : versions) {
            for (CompressionCodecName compressionCodecName : compressions) {
                for (ConnectorSession session : sessions) {
                    try (TempFile tempFile = new TempFile("test", "parquet")) {
                        JobConf jobConf = new JobConf();
                        jobConf.setEnum(COMPRESSION, compressionCodecName);
                        jobConf.setBoolean(ENABLE_DICTIONARY, true);
                        jobConf.setEnum(WRITER_VERSION, version);
                        writeParquetColumn(
                                jobConf,
                                tempFile.getFile(),
                                compressionCodecName,
                                createTableProperties(columnNames, objectInspectors),
                                getStandardStructObjectInspector(columnNames, objectInspectors),
                                getIterators(writeValues),
                                parquetSchema,
                                singleLevelArray);
                        assertFileContents(
                                session,
                                tempFile.getFile(),
                                getIterators(readValues),
                                columnNames,
                                columnTypes);
                    }
                }
            }
        }

        // write presto parquet
        for (CompressionCodecName compressionCodecName : writerCompressions) {
            for (ConnectorSession session : sessions) {
                try (TempFile tempFile = new TempFile("test", "parquet")) {
                    OptionalInt min = stream(writeValues).mapToInt(Iterables::size).min();
                    checkState(min.isPresent());
                    writeParquetColumnPresto(tempFile.getFile(), columnTypes, columnNames, getIterators(readValues), min.getAsInt(), compressionCodecName);
                    assertFileContents(
                            session,
                            tempFile.getFile(),
                            getIterators(readValues),
                            columnNames,
                            columnTypes);
                }
            }
        }
    }

    static void testMaxReadBytes(ObjectInspector objectInspector, Iterable<?> writeValues, Iterable<?> readValues, Type type, DataSize maxReadBlockSize)
            throws Exception
    {
        assertMaxReadBytes(
                singletonList(objectInspector),
                new Iterable<?>[] {writeValues},
                new Iterable<?>[] {readValues},
                TEST_COLUMN,
                singletonList(type),
                Optional.empty(),
                maxReadBlockSize);
    }

    static void assertMaxReadBytes(
            List<ObjectInspector> objectInspectors,
            Iterable<?>[] writeValues,
            Iterable<?>[] readValues,
            List<String> columnNames,
            List<Type> columnTypes,
            Optional<MessageType> parquetSchema,
            DataSize maxReadBlockSize)
            throws Exception
    {
        CompressionCodecName compressionCodecName = UNCOMPRESSED;
        HiveSessionProperties hiveSessionProperties = new HiveSessionProperties(
                new HiveConfig()
                        .setHiveStorageFormat(HiveStorageFormat.PARQUET)
                        .setUseParquetColumnNames(false),
                new OrcReaderConfig(),
                new OrcWriterConfig(),
                new ParquetReaderConfig()
                        .setMaxReadBlockSize(maxReadBlockSize),
                new ParquetWriterConfig());
        ConnectorSession session = TestingConnectorSession.builder()
                .setPropertyMetadata(hiveSessionProperties.getSessionProperties())
                .build();

        try (TempFile tempFile = new TempFile("test", "parquet")) {
            JobConf jobConf = new JobConf();
            jobConf.setEnum(COMPRESSION, compressionCodecName);
            jobConf.setBoolean(ENABLE_DICTIONARY, true);
            jobConf.setEnum(WRITER_VERSION, PARQUET_1_0);
            writeParquetColumn(
                    jobConf,
                    tempFile.getFile(),
                    compressionCodecName,
                    createTableProperties(columnNames, objectInspectors),
                    getStandardStructObjectInspector(columnNames, objectInspectors),
                    getIterators(writeValues),
                    parquetSchema,
                    false);

            Iterator<?>[] expectedValues = getIterators(readValues);
            try (ConnectorPageSource pageSource = getFileFormat().createFileFormatReader(
                    session,
                    HDFS_ENVIRONMENT,
                    tempFile.getFile(),
                    columnNames,
                    columnTypes)) {
                assertPageSource(
                        columnTypes,
                        expectedValues,
                        pageSource,
                        Optional.of(getParquetMaxReadBlockSize(session).toBytes()));
                assertFalse(stream(expectedValues).allMatch(Iterator::hasNext));
            }
        }
    }

    private static void assertFileContents(
            ConnectorSession session,
            File dataFile,
            Iterator<?>[] expectedValues,
            List<String> columnNames,
            List<Type> columnTypes)
            throws IOException
    {
        try (ConnectorPageSource pageSource = getFileFormat().createFileFormatReader(
                session,
                HDFS_ENVIRONMENT,
                dataFile,
                columnNames,
                columnTypes)) {
            if (pageSource instanceof RecordPageSource) {
                assertRecordCursor(columnTypes, expectedValues, ((RecordPageSource) pageSource).getCursor());
            }
            else {
                assertPageSource(columnTypes, expectedValues, pageSource);
            }
            assertFalse(stream(expectedValues).allMatch(Iterator::hasNext));
        }
    }

    private static void assertPageSource(List<Type> types, Iterator<?>[] valuesByField, ConnectorPageSource pageSource)
    {
        assertPageSource(types, valuesByField, pageSource, Optional.empty());
    }

    private static void assertPageSource(List<Type> types, Iterator<?>[] valuesByField, ConnectorPageSource pageSource, Optional<Long> maxReadBlockSize)
    {
        while (!pageSource.isFinished()) {
            Page page = pageSource.getNextPage();
            if (page == null) {
                continue;
            }

            maxReadBlockSize.ifPresent(max ->
                    assertTrue(page.getPositionCount() == 1 || page.getSizeInBytes() <= max));

            for (int field = 0; field < page.getChannelCount(); field++) {
                Block block = page.getBlock(field);
                for (int i = 0; i < block.getPositionCount(); i++) {
                    assertTrue(valuesByField[field].hasNext());
                    Object expected = valuesByField[field].next();
                    Object actual = decodeObject(types.get(field), block, i);
                    assertEquals(actual, expected);
                }
            }
        }
    }

    private static void assertRecordCursor(List<Type> types, Iterator<?>[] valuesByField, RecordCursor cursor)
    {
        while (cursor.advanceNextPosition()) {
            for (int field = 0; field < types.size(); field++) {
                assertTrue(valuesByField[field].hasNext());
                Object expected = valuesByField[field].next();
                Object actual = getActualCursorValue(cursor, types.get(field), field);
                assertEquals(actual, expected);
            }
        }
    }

    private static Object getActualCursorValue(RecordCursor cursor, Type type, int field)
    {
        Object fieldFromCursor = getFieldFromCursor(cursor, type, field);
        if (fieldFromCursor == null) {
            return null;
        }
        if (isStructuralType(type)) {
            Block block = (Block) fieldFromCursor;
            if (isArrayType(type)) {
                Type elementType = ((ArrayType) type).getElementType();
                return toArrayValue(block, elementType);
            }
            if (isMapType(type)) {
                MapType mapType = (MapType) type;
                return toMapValue(block, mapType.getKeyType(), mapType.getValueType());
            }
            if (isRowType(type)) {
                return toRowValue(block, type.getTypeParameters());
            }
        }
        if (type instanceof DecimalType) {
            DecimalType decimalType = (DecimalType) type;
            return new SqlDecimal((BigInteger) fieldFromCursor, decimalType.getPrecision(), decimalType.getScale());
        }
        if (type instanceof VarcharType) {
            return new String(((Slice) fieldFromCursor).getBytes(), UTF_8);
        }
        if (VARBINARY.equals(type)) {
            return new SqlVarbinary(((Slice) fieldFromCursor).getBytes());
        }
        if (DATE.equals(type)) {
            return new SqlDate(((Long) fieldFromCursor).intValue());
        }
        if (TIMESTAMP_MILLIS.equals(type)) {
            return SqlTimestamp.fromMillis(3, (long) fieldFromCursor);
        }
        return fieldFromCursor;
    }

    private static Map<?, ?> toMapValue(Block mapBlock, Type keyType, Type valueType)
    {
        Map<Object, Object> map = new HashMap<>(mapBlock.getPositionCount() * 2);
        for (int i = 0; i < mapBlock.getPositionCount(); i += 2) {
            map.put(keyType.getObjectValue(SESSION, mapBlock, i), valueType.getObjectValue(SESSION, mapBlock, i + 1));
        }
        return Collections.unmodifiableMap(map);
    }

    private static List<?> toArrayValue(Block arrayBlock, Type elementType)
    {
        List<Object> values = new ArrayList<>();
        for (int position = 0; position < arrayBlock.getPositionCount(); position++) {
            values.add(elementType.getObjectValue(SESSION, arrayBlock, position));
        }
        return Collections.unmodifiableList(values);
    }

    private static List<?> toRowValue(Block rowBlock, List<Type> fieldTypes)
    {
        List<Object> values = new ArrayList<>(rowBlock.getPositionCount());
        for (int i = 0; i < rowBlock.getPositionCount(); i++) {
            values.add(fieldTypes.get(i).getObjectValue(SESSION, rowBlock, i));
        }
        return Collections.unmodifiableList(values);
    }

    private static HiveConfig createHiveConfig(boolean useParquetColumnNames)
    {
        return new HiveConfig()
                .setHiveStorageFormat(HiveStorageFormat.PARQUET)
                .setUseParquetColumnNames(useParquetColumnNames);
    }

    private static FileFormat getFileFormat()
    {
        return OPTIMIZED ? FileFormat.TRINO_PARQUET : FileFormat.HIVE_PARQUET;
    }

    public static void writeParquetColumn(
            JobConf jobConf,
            File outputFile,
            CompressionCodecName compressionCodecName,
            Properties tableProperties,
            SettableStructObjectInspector objectInspector,
            Iterator<?>[] valuesByField,
            Optional<MessageType> parquetSchema,
            boolean singleLevelArray)
            throws Exception
    {
        RecordWriter recordWriter = new TestMapredParquetOutputFormat(parquetSchema, singleLevelArray)
                .getHiveRecordWriter(
                        jobConf,
                        new Path(outputFile.toURI()),
                        Text.class,
                        compressionCodecName != UNCOMPRESSED,
                        tableProperties,
                        () -> {});
        Object row = objectInspector.create();
        List<StructField> fields = ImmutableList.copyOf(objectInspector.getAllStructFieldRefs());
        while (stream(valuesByField).allMatch(Iterator::hasNext)) {
            for (int field = 0; field < fields.size(); field++) {
                Object value = valuesByField[field].next();
                objectInspector.setStructFieldData(row, fields.get(field), value);
            }
            ParquetHiveSerDe serde = new ParquetHiveSerDe();
            serde.initialize(jobConf, tableProperties, null);
            Writable record = serde.serialize(row, objectInspector);
            recordWriter.write(record);
        }
        recordWriter.close(false);
    }

    public static Properties createTableProperties(List<String> columnNames, List<ObjectInspector> objectInspectors)
    {
        Properties orderTableProperties = new Properties();
        orderTableProperties.setProperty("columns", Joiner.on(',').join(columnNames));
        orderTableProperties.setProperty("columns.types", Joiner.on(',').join(transform(objectInspectors, ObjectInspector::getTypeName)));
        return orderTableProperties;
    }

    public static class TempFile
            implements Closeable
    {
        private final File file;

        public TempFile(String prefix, String suffix)
        {
            try {
                file = File.createTempFile(prefix, suffix);
                verify(file.delete());
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public File getFile()
        {
            return file;
        }

        @Override
        public void close()
        {
            if (!file.delete()) {
                verify(!file.exists());
            }
        }
    }

    private static Iterator<?>[] getIterators(Iterable<?>[] values)
    {
        return stream(values)
                .map(Iterable::iterator)
                .toArray(Iterator<?>[]::new);
    }

    private Iterable<?>[] transformToNulls(Iterable<?>[] values)
    {
        return stream(values)
                .map(v -> transform(v, constant(null)))
                .toArray(Iterable<?>[]::new);
    }

    private static Iterable<?>[] reverse(Iterable<?>[] iterables)
    {
        return stream(iterables)
                .map(ImmutableList::copyOf)
                .map(Lists::reverse)
                .toArray(Iterable<?>[]::new);
    }

    private static Iterable<?>[] insertNullEvery(int n, Iterable<?>[] iterables)
    {
        return stream(iterables)
                .map(itr -> insertNullEvery(n, itr))
                .toArray(Iterable<?>[]::new);
    }

    static <T> Iterable<T> insertNullEvery(int n, Iterable<T> iterable)
    {
        return () -> new AbstractIterator<>()
        {
            private final Iterator<T> delegate = iterable.iterator();
            private int position;

            @Override
            protected T computeNext()
            {
                position++;
                if (position > n) {
                    position = 0;
                    return null;
                }

                if (!delegate.hasNext()) {
                    return endOfData();
                }

                return delegate.next();
            }
        };
    }

    private static Object decodeObject(Type type, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        return type.getObjectValue(SESSION, block, position);
    }

    private static void writeParquetColumnPresto(File outputFile, List<Type> types, List<String> columnNames, Iterator<?>[] values, int size, CompressionCodecName compressionCodecName)
            throws Exception
    {
        checkArgument(types.size() == columnNames.size() && types.size() == values.length);
        ParquetSchemaConverter schemaConverter = new ParquetSchemaConverter(types, columnNames);
        ParquetWriter writer = new ParquetWriter(
                new FileOutputStream(outputFile),
                schemaConverter.getMessageType(),
                schemaConverter.getPrimitiveTypes(),
                ParquetWriterOptions.builder()
                        .setMaxPageSize(DataSize.ofBytes(100))
                        .setMaxBlockSize(DataSize.ofBytes(100000))
                        .build(),
                compressionCodecName);

        PageBuilder pageBuilder = new PageBuilder(types);
        for (int i = 0; i < types.size(); ++i) {
            Type type = types.get(i);
            Iterator<?> iterator = values[i];
            BlockBuilder blockBuilder = pageBuilder.getBlockBuilder(i);

            for (int j = 0; j < size; ++j) {
                checkState(iterator.hasNext());
                Object value = iterator.next();
                writeValue(type, blockBuilder, value);
            }
        }
        pageBuilder.declarePositions(size);
        writer.write(pageBuilder.build());
        writer.close();
    }

    private static void writeValue(Type type, BlockBuilder blockBuilder, Object value)
    {
        if (value == null) {
            blockBuilder.appendNull();
        }
        else {
            if (BOOLEAN.equals(type)) {
                type.writeBoolean(blockBuilder, (Boolean) value);
            }
            else if (TINYINT.equals(type) || SMALLINT.equals(type) || INTEGER.equals(type) || BIGINT.equals(type)) {
                type.writeLong(blockBuilder, ((Number) value).longValue());
            }
            else if (Decimals.isShortDecimal(type)) {
                type.writeLong(blockBuilder, ((SqlDecimal) value).getUnscaledValue().longValue());
            }
            else if (Decimals.isLongDecimal(type)) {
                if (Decimals.overflows(((SqlDecimal) value).getUnscaledValue(), MAX_PRECISION_INT64)) {
                    type.writeSlice(blockBuilder, Decimals.encodeUnscaledValue(((SqlDecimal) value).toBigDecimal().unscaledValue()));
                }
                else {
                    type.writeSlice(blockBuilder, Decimals.encodeUnscaledValue(((SqlDecimal) value).getUnscaledValue().longValue()));
                }
            }
            else if (DOUBLE.equals(type)) {
                type.writeDouble(blockBuilder, ((Number) value).doubleValue());
            }
            else if (REAL.equals(type)) {
                float floatValue = ((Number) value).floatValue();
                type.writeLong(blockBuilder, Float.floatToIntBits(floatValue));
            }
            else if (type instanceof VarcharType) {
                Slice slice = truncateToLength(utf8Slice((String) value), type);
                type.writeSlice(blockBuilder, slice);
            }
            else if (type instanceof CharType) {
                Slice slice = truncateToLengthAndTrimSpaces(utf8Slice((String) value), type);
                type.writeSlice(blockBuilder, slice);
            }
            else if (VARBINARY.equals(type)) {
                type.writeSlice(blockBuilder, Slices.wrappedBuffer(((SqlVarbinary) value).getBytes()));
            }
            else if (DATE.equals(type)) {
                long days = ((SqlDate) value).getDays();
                type.writeLong(blockBuilder, days);
            }
            else if (TIMESTAMP_MILLIS.equals(type)) {
                type.writeLong(blockBuilder, ((SqlTimestamp) value).getEpochMicros());
            }
            else {
                if (type instanceof ArrayType) {
                    List<?> array = (List<?>) value;
                    Type elementType = type.getTypeParameters().get(0);
                    BlockBuilder arrayBlockBuilder = blockBuilder.beginBlockEntry();
                    for (Object elementValue : array) {
                        writeValue(elementType, arrayBlockBuilder, elementValue);
                    }
                    blockBuilder.closeEntry();
                }
                else if (type instanceof MapType) {
                    Map<?, ?> map = (Map<?, ?>) value;
                    Type keyType = type.getTypeParameters().get(0);
                    Type valueType = type.getTypeParameters().get(1);
                    BlockBuilder mapBlockBuilder = blockBuilder.beginBlockEntry();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        writeValue(keyType, mapBlockBuilder, entry.getKey());
                        writeValue(valueType, mapBlockBuilder, entry.getValue());
                    }
                    blockBuilder.closeEntry();
                }
                else if (type instanceof RowType) {
                    List<?> array = (List<?>) value;
                    List<Type> fieldTypes = type.getTypeParameters();
                    BlockBuilder rowBlockBuilder = blockBuilder.beginBlockEntry();
                    for (int fieldId = 0; fieldId < fieldTypes.size(); fieldId++) {
                        Type fieldType = fieldTypes.get(fieldId);
                        writeValue(fieldType, rowBlockBuilder, array.get(fieldId));
                    }
                    blockBuilder.closeEntry();
                }
                else {
                    throw new IllegalArgumentException("Unsupported type " + type);
                }
            }
        }
    }

    // copied from Parquet code to determine the max decimal precision supported by INT32/INT64
    private static long maxPrecision(int numBytes)
    {
        return Math.round(Math.floor(Math.log10(Math.pow(2, 8 * numBytes - 1) - 1)));
    }
}