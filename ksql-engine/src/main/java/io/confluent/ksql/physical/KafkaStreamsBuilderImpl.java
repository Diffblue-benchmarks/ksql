/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.ksql.physical;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.streams.KafkaClientSupplier;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier;

public class KafkaStreamsBuilderImpl implements KafkaStreamsBuilder {

  private final KafkaClientSupplier clientSupplier;

  public KafkaStreamsBuilderImpl() {
    this(new DefaultKafkaClientSupplier());
  }

  public KafkaStreamsBuilderImpl(final KafkaClientSupplier clientSupplier) {
    Objects.requireNonNull(clientSupplier, "clientSupplier can't be null");
    this.clientSupplier = clientSupplier;
  }

  @Override
  public KafkaStreams buildKafkaStreams(
      final StreamsBuilder builder,
      final Map<String, Object> conf) {

    final Properties props = new Properties();
    props.putAll(conf);
    return new KafkaStreams(builder.build(), props, clientSupplier);
  }
}
