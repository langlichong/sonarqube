/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.computation.measure;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.rules.ExternalResource;
import org.sonar.db.rule.RuleDto;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.component.ComponentVisitor;
import org.sonar.server.computation.component.CrawlerDepthLimit;
import org.sonar.server.computation.component.DepthTraversalTypeAwareCrawler;
import org.sonar.server.computation.component.TreeRootHolder;
import org.sonar.server.computation.component.TypeAwareVisitorAdapter;
import org.sonar.server.computation.debt.Characteristic;
import org.sonar.server.computation.metric.Metric;
import org.sonar.server.computation.metric.MetricRepositoryRule;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.FluentIterable.from;
import static com.google.common.collect.Maps.filterKeys;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.sonar.server.computation.component.Component.Type.FILE;
import static org.sonar.server.computation.component.Component.Type.PROJECT_VIEW;

/**
 * An implementation of MeasureRepository as a JUnit rule which provides add methods for raw measures and extra add
 * methods that takes component ref and metric keys thanks to the integration with various Component and Metric
 * providers.
 */
public class MeasureRepositoryRule extends ExternalResource implements MeasureRepository {
  @CheckForNull
  private final ComponentProvider componentProvider;
  @CheckForNull
  private final MetricRepositoryRule metricRepositoryRule;
  private final Map<InternalKey, Measure> baseMeasures = new HashMap<>();
  private final Map<InternalKey, Measure> rawMeasures = new HashMap<>();
  private final Map<InternalKey, Measure> initialRawMeasures = new HashMap<>();
  private final Predicate<Map.Entry<InternalKey, Measure>> isAddedMeasure = new Predicate<Map.Entry<InternalKey, Measure>>() {
    @Override
    public boolean apply(@Nonnull Map.Entry<InternalKey, Measure> input) {
      return !initialRawMeasures.containsKey(input.getKey())
        || !MeasureRepoEntry.deepEquals(input.getValue(), initialRawMeasures.get(input.getKey()));
    }
  };

  private MeasureRepositoryRule(@Nullable ComponentProvider componentProvider, @Nullable MetricRepositoryRule metricRepositoryRule) {
    this.componentProvider = componentProvider;
    this.metricRepositoryRule = metricRepositoryRule;
  }

  @Override
  protected void after() {
    if (componentProvider != null) {
      componentProvider.reset();
    }
    baseMeasures.clear();
    rawMeasures.clear();
  }

  public static MeasureRepositoryRule create() {
    return new MeasureRepositoryRule(null, null);
  }

  public static MeasureRepositoryRule create(TreeRootHolder treeRootHolder, MetricRepositoryRule metricRepositoryRule) {
    return new MeasureRepositoryRule(new TreeRootHolderComponentProvider(treeRootHolder), requireNonNull(metricRepositoryRule));
  }

  public static MeasureRepositoryRule create(Component treeRoot, MetricRepositoryRule metricRepositoryRule) {
    return new MeasureRepositoryRule(new TreeComponentProvider(treeRoot), requireNonNull(metricRepositoryRule));
  }

  public MeasureRepositoryRule addBaseMeasure(Component component, Metric metric, Measure measure) {
    checkBaseMeasureArgument(measure);

    checkAndInitProvidersState();

    InternalKey internalKey = new InternalKey(component, metric);
    checkState(!baseMeasures.containsKey(internalKey),
      format("Can not add a BaseMeasure twice for a Component (ref=%s) and Metric (key=%s)", getRef(component), metric.getKey()));

    baseMeasures.put(internalKey, measure);

    return this;
  }

  public MeasureRepositoryRule addBaseMeasure(int componentRef, String metricKey, Measure measure) {
    checkBaseMeasureArgument(measure);

    checkAndInitProvidersState();

    InternalKey internalKey = new InternalKey(componentProvider.getByRef(componentRef), metricRepositoryRule.getByKey(metricKey));
    checkState(!baseMeasures.containsKey(internalKey), format("Can not add a BaseMeasure twice for a Component (ref=%s) and Metric (key=%s)", componentRef, metricKey));

    baseMeasures.put(internalKey, measure);

    return this;
  }

  private static void checkBaseMeasureArgument(Measure measure) {
    checkArgument(measure.getRuleId() == null && measure.getCharacteristicId() == null, "A Base measure can not have ruleId nor a characteristicId");
  }

  public SetMultimap<String, Measure> getRawMeasures(int componentRef) {
    return getRawMeasures(componentProvider.getByRef(componentRef));
  }

  /**
   * Return measures that were added by the step (using {@link #add(Component, Metric, Measure)}).
   * It does not contain the one added in the test by {@link #addRawMeasure(int, String, Measure)}
   */
  public SetMultimap<String, Measure> getAddedRawMeasures(int componentRef) {
    checkAndInitProvidersState();

    return getAddedRawMeasures(componentProvider.getByRef(componentRef));
  }

  /**
   * Return a measure that were added by the step (using {@link #add(Component, Metric, Measure)}).
   * It does not contain the one added in the test by {@link #addRawMeasure(int, String, Measure)}
   */
  public Optional<Measure> getAddedRawMeasure(int componentRef, String metricKey) {
    checkAndInitProvidersState();

    Set<Measure> measures = getAddedRawMeasures(componentProvider.getByRef(componentRef)).get(metricKey);
    if (measures.isEmpty()) {
      return Optional.absent();
    } else if (measures.size() != 1) {
      throw new IllegalArgumentException(String.format("There is more than one measure on metric '%s' for component '%s'", metricKey, componentRef));
    }
    return Optional.of(measures.iterator().next());
  }

  /**
   * Return measures that were added by the step (using {@link #add(Component, Metric, Measure)}).
   * It does not contain the one added in the test by {@link #addRawMeasure(int, String, Measure)}
   */
  public SetMultimap<String, Measure> getAddedRawMeasures(Component component) {
    checkAndInitProvidersState();

    ImmutableSetMultimap.Builder<String, Measure> builder = ImmutableSetMultimap.builder();
    for (Map.Entry<InternalKey, Measure> entry : from(filterKeys(rawMeasures, hasComponentRef(component)).entrySet()).filter(isAddedMeasure)) {
      builder.put(entry.getKey().getMetricKey(), entry.getValue());
    }
    return builder.build();
  }

  public MeasureRepositoryRule addRawMeasure(int componentRef, String metricKey, Measure measure) {
    checkAndInitProvidersState();

    InternalKey internalKey = new InternalKey(componentProvider.getByRef(componentRef), metricRepositoryRule.getByKey(metricKey), measure.getRuleId(),
      measure.getCharacteristicId());
    checkState(!rawMeasures.containsKey(internalKey), format(
      "A measure can only be set once for Component (ref=%s), Metric (key=%s), ruleId=%s, characteristicId=%s",
      componentRef, metricKey, measure.getRuleId(), measure.getCharacteristicId()));

    rawMeasures.put(internalKey, measure);
    initialRawMeasures.put(internalKey, measure);

    return this;
  }

  @Override
  public Optional<Measure> getBaseMeasure(Component component, Metric metric) {
    return Optional.fromNullable(baseMeasures.get(new InternalKey(component, metric)));
  }

  @Override
  public Optional<Measure> getRawMeasure(Component component, Metric metric) {
    return Optional.fromNullable(rawMeasures.get(new InternalKey(component, metric)));
  }

  @Override
  public Optional<Measure> getRawMeasure(Component component, Metric metric, RuleDto rule) {
    return Optional.fromNullable(rawMeasures.get(new InternalKey(component, metric, rule.getId(), null)));
  }

  public Optional<Measure> getRawRuleMeasure(Component component, Metric metric, int ruleId) {
    return Optional.fromNullable(rawMeasures.get(new InternalKey(component, metric, ruleId, null)));
  }

  @Override
  public Optional<Measure> getRawMeasure(Component component, Metric metric, Characteristic characteristic) {
    return Optional.fromNullable(rawMeasures.get(new InternalKey(component, metric, null, characteristic.getId())));
  }

  public Optional<Measure> getRawCharacteristicMeasure(Component component, Metric metric, int characteristicId) {
    return Optional.fromNullable(rawMeasures.get(new InternalKey(component, metric, null, characteristicId)));
  }

  @Override
  public SetMultimap<String, Measure> getRawMeasures(Component component) {
    ImmutableSetMultimap.Builder<String, Measure> builder = ImmutableSetMultimap.builder();
    for (Map.Entry<InternalKey, Measure> entry : filterKeys(rawMeasures, hasComponentRef(component)).entrySet()) {
      builder.put(entry.getKey().getMetricKey(), entry.getValue());
    }
    return builder.build();
  }

  private HasComponentRefPredicate hasComponentRef(Component component) {
    return new HasComponentRefPredicate(component);
  }

  @Override
  public void add(Component component, Metric metric, Measure measure) {
    String ref = getRef(component);
    InternalKey internalKey = new InternalKey(ref, metric.getKey(), measure.getRuleId(), measure.getCharacteristicId());
    if (rawMeasures.containsKey(internalKey)) {
      throw new UnsupportedOperationException(format(
        "A measure can only be set once for Component (ref=%s), Metric (key=%s), ruleId=%s, characteristicId=%s",
        ref, metric.getKey(), measure.getRuleId(), measure.getCharacteristicId()));
    }
    rawMeasures.put(internalKey, measure);
  }

  @Override
  public void update(Component component, Metric metric, Measure measure) {
    String componentRef = getRef(component);
    InternalKey internalKey = new InternalKey(componentRef, metric.getKey(), measure.getRuleId(), measure.getCharacteristicId());
    if (!rawMeasures.containsKey(internalKey)) {
      throw new UnsupportedOperationException(format(
        "A measure can only be updated if it has been added first for Component (ref=%s), Metric (key=%s), ruleId=%s, characteristicId=%s",
        componentRef, metric.getKey(), measure.getRuleId(), measure.getCharacteristicId()));
    }
    rawMeasures.put(internalKey, measure);
  }

  private void checkAndInitProvidersState() {
    checkState(componentProvider != null, "Can not add a measure by Component ref if MeasureRepositoryRule has not been created for some Component provider");
    checkState(metricRepositoryRule != null, "Can not add a measure by metric key if MeasureRepositoryRule has not been created for a MetricRepository");
    componentProvider.init();
  }

  public boolean isEmpty() {
    return rawMeasures.isEmpty();
  }

  private static final class InternalKey {
    private static final int DEFAULT_VALUE = -9876;

    private final String componentRef;
    private final String metricKey;
    private final int ruleId;
    private final int characteristicId;

    public InternalKey(Component component, Metric metric) {
      this(getRef(component), metric.getKey(), null, null);
    }

    public InternalKey(Component component, Metric metric, @Nullable Integer ruleId, @Nullable Integer characteristicId) {
      this(getRef(component), metric.getKey(), ruleId, characteristicId);
    }

    public InternalKey(String componentRef, String metricKey) {
      this(componentRef, metricKey, null, null);
    }

    public InternalKey(String componentRef, String metricKey, @Nullable Integer ruleId, @Nullable Integer characteristicId) {
      this.componentRef = componentRef;
      this.metricKey = metricKey;
      this.ruleId = ruleId == null ? DEFAULT_VALUE : ruleId;
      this.characteristicId = characteristicId == null ? DEFAULT_VALUE : characteristicId;
    }

    public String getComponentRef() {
      return componentRef;
    }

    public String getMetricKey() {
      return metricKey;
    }

    public int getRuleId() {
      return ruleId;
    }

    public int getCharacteristicId() {
      return characteristicId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      InternalKey that = (InternalKey) o;
      return Objects.equals(componentRef, that.componentRef) &&
        Objects.equals(ruleId, that.ruleId) &&
        Objects.equals(characteristicId, that.characteristicId) &&
        Objects.equals(metricKey, that.metricKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(componentRef, metricKey, ruleId, characteristicId);
    }

    @Override
    public String toString() {
      return "InternalKey{" +
        "component=" + componentRef +
        ", metric='" + metricKey + '\'' +
        ", rule=" + ruleId +
        ", characteristic=" + characteristicId +
        '}';
    }
  }

  private static class HasComponentRefPredicate implements Predicate<InternalKey> {

    private final String componentRef;

    public HasComponentRefPredicate(Component component) {
      this.componentRef = getRef(component);
    }

    @Override
    public boolean apply(@Nonnull InternalKey input) {
      return input.getComponentRef().equals(this.componentRef);
    }
  }

  private interface ComponentProvider {
    void init();

    void reset();

    /**
     * @throws IllegalStateException if no component is found for the specified ref
     */
    Component getByRef(int componentRef);
  }

  private static final class TreeRootHolderComponentProvider implements ComponentProvider {
    private final TreeRootHolder treeRootHolder;
    private TreeComponentProvider delegate;

    public TreeRootHolderComponentProvider(TreeRootHolder treeRootHolder) {
      this.treeRootHolder = treeRootHolder;
    }

    @Override
    public void init() {
      if (this.delegate == null) {
        this.delegate = new TreeComponentProvider(treeRootHolder.getRoot());
        this.delegate.init();
      }
    }

    @Override
    public void reset() {
      this.delegate = null;
    }

    @Override
    public Component getByRef(int componentRef) {
      return delegate.getByRef(componentRef);
    }
  }

  private static final class TreeComponentProvider implements ComponentProvider {
    private final Map<String, Component> componentsByRef = new HashMap<>();

    public TreeComponentProvider(Component root) {
      new DepthTraversalTypeAwareCrawler(
        new TypeAwareVisitorAdapter(CrawlerDepthLimit.reportMaxDepth(FILE).withViewsMaxDepth(PROJECT_VIEW), ComponentVisitor.Order.PRE_ORDER) {
          @Override
          public void visitAny(Component component) {
            String ref = getRef(component);
            checkState(!componentsByRef.containsKey(ref), "Tree contains more than one component with ref " + ref);
            componentsByRef.put(ref, component);
          }
        }).visit(root);
    }

    @Override
    public void init() {
      // nothing to do, init done in constructor
    }

    @Override
    public void reset() {
      // we can not reset
    }

    @Override
    public Component getByRef(int componentRef) {
      Component component = componentsByRef.get(String.valueOf(componentRef));
      checkState(component != null, "Can not find Component for ref " + componentRef);
      return component;
    }
  }

  private static String getRef(Component component) {
    return component.getType().isReportType() ? String.valueOf(component.getReportAttributes().getRef()) : component.getKey();
  }

}
