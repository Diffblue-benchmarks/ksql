/*
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql.structured;

import com.google.common.collect.ImmutableList;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.codegen.CodeGenRunner;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.parser.tree.DereferenceExpression;
import io.confluent.ksql.parser.tree.Expression;
import io.confluent.ksql.parser.tree.QualifiedNameReference;
import io.confluent.ksql.planner.plan.OutputNode;
import io.confluent.ksql.util.ExpressionMetadata;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.SchemaUtil;
import io.confluent.ksql.util.SelectExpression;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.streams.kstream.WindowedSerdes;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class SchemaKStream<K> {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling

  public enum Type { SOURCE, PROJECT, FILTER, AGGREGATE, SINK, REKEY, JOIN, TOSTREAM }

  final Schema schema;
  final KStream<K, GenericRow> kstream;
  final Field keyField;
  final List<SchemaKStream> sourceSchemaKStreams;
  final Type type;
  final KsqlConfig ksqlConfig;
  final FunctionRegistry functionRegistry;
  private OutputNode output;
  final SchemaRegistryClient schemaRegistryClient;
  final Serde<K> keySerde;

  public SchemaKStream(
      final Schema schema,
      final KStream<K, GenericRow> kstream,
      final Field keyField,
      final List<SchemaKStream> sourceSchemaKStreams,
      final Serde<K> keySerde,
      final Type type,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry,
      final SchemaRegistryClient schemaRegistryClient
  ) {
    this.schema = schema;
    this.kstream = kstream;
    this.keyField = keyField;
    this.sourceSchemaKStreams = sourceSchemaKStreams;
    this.type = type;
    this.ksqlConfig = Objects.requireNonNull(ksqlConfig, "ksqlConfig");
    this.functionRegistry = functionRegistry;
    this.schemaRegistryClient = schemaRegistryClient;
    this.keySerde = Objects.requireNonNull(keySerde, "keySerde");
  }

  public QueuedSchemaKStream toQueue() {
    return new QueuedSchemaKStream<>(this);
  }

  public Serde<K> getKeySerde() {
    return keySerde;
  }

  public boolean hasWindowedKey() {
    return keySerde instanceof WindowedSerdes.SessionWindowedSerde
        || keySerde instanceof WindowedSerdes.TimeWindowedSerde;
  }

  public SchemaKStream into(
      final String kafkaTopicName,
      final Serde<GenericRow> topicValueSerDe,
      final Set<Integer> rowkeyIndexes
  ) {
    kstream
        .mapValues(row -> {
          if (row == null) {
            return null;
          }
          final List<Object> columns = new ArrayList<>();
          for (int i = 0; i < row.getColumns().size(); i++) {
            if (!rowkeyIndexes.contains(i)) {
              columns.add(row.getColumns().get(i));
            }
          }
          return new GenericRow(columns);
        }).to(kafkaTopicName, Produced.with(keySerde, topicValueSerDe));
    return this;
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> filter(final Expression filterExpression) {
    final SqlPredicate predicate = new SqlPredicate(filterExpression, schema, hasWindowedKey(),
        ksqlConfig, functionRegistry);

    final KStream<K, GenericRow> filteredKStream = kstream.filter(predicate.getPredicate());
    return new SchemaKStream<>(
        schema,
        filteredKStream,
        keyField,
        Collections.singletonList(this),
        keySerde,
        Type.FILTER,
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient
    );
  }

  public SchemaKStream<K> select(final List<SelectExpression> selectExpressions) {
    final Selection selection = new Selection(selectExpressions);
    return new SchemaKStream<>(
        selection.getSchema(),
        kstream.mapValues(selection.getSelectValueMapper()),
        selection.getKey(),
        Collections.singletonList(this),
        keySerde,
        Type.PROJECT,
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient
    );
  }

  class Selection {
    private final Schema schema;
    private final Field key;
    private final SelectValueMapper selectValueMapper;

    Selection(final List<SelectExpression> selectExpressions) {
      key = findKeyField(selectExpressions);
      final List<ExpressionMetadata> expressionEvaluators = buildExpressions(selectExpressions);
      schema = buildSchema(selectExpressions, expressionEvaluators);
      final List<String> selectFieldNames = selectExpressions.stream()
          .map(SelectExpression::getName)
          .collect(Collectors.toList());
      selectValueMapper = new SelectValueMapper(selectFieldNames, expressionEvaluators);
    }

    private Field findKeyField(final List<SelectExpression> selectExpressions) {
      if (getKeyField() == null) {
        return null;
      }
      if (getKeyField().index() == -1) {
        // The key "field" isn't an actual field in the schema
        return getKeyField();
      }
      for (int i = 0; i < selectExpressions.size(); i++) {
        final String toName = selectExpressions.get(i).getName();
        final Expression toExpression = selectExpressions.get(i).getExpression();

        /*
         * Sometimes a column reference is a DereferenceExpression, and sometimes its
         * a QualifiedNameReference. We have an issue
         * (https://github.com/confluentinc/ksql/issues/1695)
         * to track cleaning this up and using DereferenceExpression for all column references.
         * Until then, we have to check for both here.
         */
        if (toExpression instanceof DereferenceExpression) {
          final DereferenceExpression dereferenceExpression
              = (DereferenceExpression) toExpression;
          if (SchemaUtil.matchFieldName(getKeyField(), dereferenceExpression.toString())) {
            return new Field(toName, i, getKeyField().schema());
          }
        } else if (toExpression instanceof QualifiedNameReference) {
          final QualifiedNameReference qualifiedNameReference
              = (QualifiedNameReference) toExpression;
          if (SchemaUtil.matchFieldName(
              getKeyField(),
              qualifiedNameReference.getName().getSuffix())) {
            return new Field(toName, i, getKeyField().schema());
          }
        }
      }
      return null;
    }

    private Schema buildSchema(
        final List<SelectExpression> selectExpressions,
        final List<ExpressionMetadata> expressionEvaluators) {
      final SchemaBuilder schemaBuilder = SchemaBuilder.struct();
      IntStream.range(0, selectExpressions.size()).forEach(
          i -> schemaBuilder.field(
              selectExpressions.get(i).getName(),
              expressionEvaluators.get(i).getExpressionType()));
      return schemaBuilder.build();
    }

    List<ExpressionMetadata> buildExpressions(final List<SelectExpression> selectExpressions
    ) {
      final Stream<Expression> expressions = selectExpressions.stream()
          .map(SelectExpression::getExpression);

      return CodeGenRunner.compileExpressions(
          expressions, "Select", SchemaKStream.this.getSchema(), ksqlConfig, functionRegistry);
    }

    public Schema getSchema() {
      return schema;
    }

    public Field getKey() {
      return key;
    }

    SelectValueMapper getSelectValueMapper() {
      return selectValueMapper;
    }
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> leftJoin(
      final SchemaKTable<K> schemaKTable,
      final Schema joinSchema,
      final Field joinKey,
      final Serde<GenericRow> leftValueSerDe
  ) {

    final KStream<K, GenericRow> joinedKStream =
        kstream.leftJoin(
            schemaKTable.getKtable(),
            new KsqlValueJoiner(this.getSchema(), schemaKTable.getSchema()),
            Joined.with(keySerde, leftValueSerDe, null)
        );

    return new SchemaKStream(
        joinSchema,
        joinedKStream,
        joinKey,
        ImmutableList.of(this, schemaKTable),
        keySerde,
        Type.JOIN,
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient
    );
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> leftJoin(
      final SchemaKStream<K> otherSchemaKStream,
      final Schema joinSchema,
      final Field joinKey,
      final JoinWindows joinWindows,
      final Serde<GenericRow> leftSerde,
      final Serde<GenericRow> rightSerde) {

    final KStream<K, GenericRow> joinStream =
        kstream
            .leftJoin(
                otherSchemaKStream.kstream,
                new KsqlValueJoiner(this.getSchema(), otherSchemaKStream.getSchema()),
                joinWindows,
                Joined.with(keySerde, leftSerde, rightSerde)
            );

    return new SchemaKStream<>(
        joinSchema,
        joinStream,
        joinKey,
        ImmutableList.of(this, otherSchemaKStream),
        keySerde,
        Type.JOIN,
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient);
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> join(
      final SchemaKTable<K> schemaKTable,
      final Schema joinSchema,
      final Field joinKey,
      final Serde<GenericRow> joinSerDe
  ) {

    final KStream<K, GenericRow> joinedKStream =
        kstream.join(
            schemaKTable.getKtable(),
            new KsqlValueJoiner(this.getSchema(), schemaKTable.getSchema()),
            Joined.with(keySerde, joinSerDe, null)
        );

    return new SchemaKStream<>(
        joinSchema,
        joinedKStream,
        joinKey,
        ImmutableList.of(this, schemaKTable),
        keySerde,
        Type.JOIN,
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient
    );
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> join(
      final SchemaKStream<K> otherSchemaKStream,
      final Schema joinSchema,
      final Field joinKey,
      final JoinWindows joinWindows,
      final Serde<GenericRow> leftSerde,
      final Serde<GenericRow> rightSerde) {

    final KStream<K, GenericRow> joinStream =
        kstream
            .join(
                otherSchemaKStream.kstream,
                new KsqlValueJoiner(this.getSchema(), otherSchemaKStream.getSchema()),
                joinWindows,
                Joined.with(keySerde, leftSerde, rightSerde)
            );

    return new SchemaKStream<>(
        joinSchema,
        joinStream,
        joinKey,
        ImmutableList.of(this, otherSchemaKStream),
        keySerde,
        Type.JOIN,
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient);
  }

  public SchemaKStream<K> outerJoin(
      final SchemaKStream<K> otherSchemaKStream,
      final Schema joinSchema,
      final Field joinKey,
      final JoinWindows joinWindows,
      final Serde<GenericRow> leftSerde,
      final Serde<GenericRow> rightSerde) {

    final KStream<K, GenericRow> joinStream = kstream
        .outerJoin(
            otherSchemaKStream.kstream,
            new KsqlValueJoiner(this.getSchema(), otherSchemaKStream.getSchema()),
            joinWindows,
            Joined.with(keySerde, leftSerde, rightSerde)
        );

    return new SchemaKStream<>(
        joinSchema,
        joinStream,
        joinKey,
        ImmutableList.of(this, otherSchemaKStream),
        keySerde,
        Type.JOIN,
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient);
  }


  @SuppressWarnings("unchecked")
  public SchemaKStream<?> selectKey(final Field newKeyField, final boolean updateRowKey) {
    if (keyField != null && keyField.name().equals(newKeyField.name())) {
      return this;
    }

    final KStream keyedKStream = kstream
        .filter((key, value) -> value != null
            && extractColumn(newKeyField, value) != null)
        .selectKey((key, value) -> extractColumn(newKeyField, value).toString())
        .mapValues((key, row) -> {
          if (updateRowKey) {
            row.getColumns().set(SchemaUtil.ROWKEY_NAME_INDEX, key);
          }
          return row;
        });

    return new SchemaKStream<>(
        schema,
        keyedKStream,
        newKeyField,
        Collections.singletonList(this),
        Serdes.String(),
        Type.REKEY,
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient
    );
  }

  private Object extractColumn(final Field newKeyField, final GenericRow value) {
    return value
        .getColumns()
        .get(SchemaUtil.getFieldIndexByName(schema, newKeyField.name()));
  }

  private String fieldNameFromExpression(final Expression expression) {
    if (expression instanceof DereferenceExpression) {
      final DereferenceExpression dereferenceExpression =
          (DereferenceExpression) expression;
      return dereferenceExpression.getFieldName();
    } else if (expression instanceof QualifiedNameReference) {
      final QualifiedNameReference qualifiedNameReference = (QualifiedNameReference) expression;
      return qualifiedNameReference.getName().toString();
    }
    return null;
  }

  private boolean rekeyRequired(final List<Expression> groupByExpressions) {
    final Field keyField = getKeyField();
    if (keyField == null) {
      return true;
    }
    final String keyFieldName = SchemaUtil.getFieldNameWithNoAlias(keyField);
    return !(groupByExpressions.size() == 1
        && fieldNameFromExpression(groupByExpressions.get(0)).equals(keyFieldName));
  }

  public SchemaKGroupedStream groupBy(
      final Serde<GenericRow> valSerde,
      final List<Expression> groupByExpressions) {
    final boolean rekey = rekeyRequired(groupByExpressions);

    if (!rekey) {
      final KGroupedStream kgroupedStream = kstream.groupByKey(Grouped.with(keySerde, valSerde));
      return new SchemaKGroupedStream(
          schema,
          kgroupedStream,
          keyField,
          Collections.singletonList(this),
          ksqlConfig,
          functionRegistry,
          schemaRegistryClient
      );
    }

    final GroupBy groupBy = new GroupBy(groupByExpressions);

    final KGroupedStream kgroupedStream = kstream
        .filter((key, value) -> value != null)
        .groupBy(groupBy.mapper, Grouped.with(Serdes.String(), valSerde));

    // TODO: if the key is a prefix of the grouping columns then we can
    //       use the repartition reflection hack to tell streams not to
    //       repartition.
    final Field newKeyField = new Field(
        groupBy.aggregateKeyName, -1, Schema.OPTIONAL_STRING_SCHEMA);
    return new SchemaKGroupedStream(
        schema,
        kgroupedStream,
        newKeyField,
        Collections.singletonList(this),
        ksqlConfig,
        functionRegistry,
        schemaRegistryClient);
  }

  public Field getKeyField() {
    return keyField;
  }

  public Schema getSchema() {
    return schema;
  }

  public KStream<K, GenericRow> getKstream() {
    return kstream;
  }

  public List<SchemaKStream> getSourceSchemaKStreams() {
    return sourceSchemaKStreams;
  }

  public String getExecutionPlan(final String indent) {
    final StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append(indent)
        .append(" > [ ")
        .append(type).append(" ] Schema: ")
        .append(SchemaUtil.getSchemaDefinitionString(schema))
        .append(".\n");
    for (final SchemaKStream schemaKStream : sourceSchemaKStreams) {
      stringBuilder
          .append("\t")
          .append(indent)
          .append(schemaKStream.getExecutionPlan(indent + "\t"));
    }
    return stringBuilder.toString();
  }

  public OutputNode outputNode() {
    return output;
  }

  public void setOutputNode(final OutputNode output) {
    this.output = output;
  }

  public Type getType() {
    return type;
  }

  public FunctionRegistry getFunctionRegistry() {
    return functionRegistry;
  }

  public SchemaRegistryClient getSchemaRegistryClient() {
    return schemaRegistryClient;
  }

  class GroupBy {

    final String aggregateKeyName;
    final GroupByMapper<Object> mapper;

    GroupBy(final List<Expression> expressions) {
      final List<ExpressionMetadata> groupBy = CodeGenRunner.compileExpressions(
          expressions.stream(), "Group By", schema, ksqlConfig, functionRegistry);

      this.mapper = new GroupByMapper<>(groupBy);
      this.aggregateKeyName = GroupByMapper.keyNameFor(expressions);
    }
  }

  protected static class KsqlValueJoiner
      implements ValueJoiner<GenericRow, GenericRow, GenericRow> {
    private final Schema leftSchema;
    private final Schema rightSchema;

    KsqlValueJoiner(final Schema leftSchema, final Schema rightSchema) {
      this.leftSchema = leftSchema;
      this.rightSchema = rightSchema;
    }

    @Override
    public GenericRow apply(final GenericRow left, final GenericRow right) {
      final List<Object> columns = new ArrayList<>();
      if (left != null) {
        columns.addAll(left.getColumns());
      } else {
        fillWithNulls(columns, leftSchema.fields().size());
      }

      if (right != null) {
        columns.addAll(right.getColumns());
      } else {
        fillWithNulls(columns, rightSchema.fields().size());
      }

      return new GenericRow(columns);
    }

    private void fillWithNulls(final List<Object> columns, final int numToFill) {
      for (int i = 0; i < numToFill; ++i) {
        columns.add(null);
      }
    }
  }

}
