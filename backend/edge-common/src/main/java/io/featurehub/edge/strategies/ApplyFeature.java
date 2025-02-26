package io.featurehub.edge.strategies;

import io.featurehub.mr.model.RolloutStrategyAttributeConditional;
import io.featurehub.mr.model.RolloutStrategyFieldType;
import io.featurehub.sse.model.FeatureRolloutStrategy;
import io.featurehub.sse.model.FeatureRolloutStrategyAttribute;
import io.featurehub.strategies.matchers.MatcherRepository;
import io.featurehub.strategies.percentage.PercentageCalculator;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ApplyFeature {
  private static final Logger log = LoggerFactory.getLogger(ApplyFeature.class);
  private final PercentageCalculator percentageCalculator;
  private final MatcherRepository matcherRepository;

  @Inject
  public ApplyFeature(PercentageCalculator percentageCalculator, MatcherRepository matcherRepository) {
    this.percentageCalculator = percentageCalculator;
    this.matcherRepository = matcherRepository;
  }

  public Applied applyFeature(List<FeatureRolloutStrategy> strategies, String key, String featureValueId,
                              ClientContext cac) {
    if (cac != null & strategies != null && !strategies.isEmpty()) {
      Integer percentage = null;
      String percentageKey = null;
      Map<String, Integer> basePercentage = new HashMap<>();
      String defaultPercentageKey = cac.defaultPercentageKey();

      for(FeatureRolloutStrategy rsi : strategies ) {
        if (rsi.getPercentage() != null && (defaultPercentageKey != null || (rsi.getPercentageAttributes() != null && !rsi.getPercentageAttributes().isEmpty()))) {
          // determine what the percentage key is
          String newPercentageKey = determinePercentageKey(cac, rsi.getPercentageAttributes());

          int basePercentageVal = basePercentage.computeIfAbsent(newPercentageKey, (k) -> 0);
          // if we have changed the key or we have never calculated it, calculate it and set the
          // base percentage to null
          if (percentage == null || !newPercentageKey.equals(percentageKey)) {
            percentageKey = newPercentageKey;

            percentage = percentageCalculator.determineClientPercentage(percentageKey,
              featureValueId);
            log.trace("percentage for {} on {} calculated at {}", defaultPercentageKey, key, percentage);
          }

          log.trace("comparing actual {} vs required: {}", percentage, rsi.getPercentage());
          int useBasePercentage = rsi.getAttributes().isEmpty() ? basePercentageVal : 0;
            // if the percentage is lower than the user's key +
            // id of feature value then apply it
          if (percentage <= (useBasePercentage + rsi.getPercentage())) {
            if (!rsi.getAttributes().isEmpty()) {
              if (matchAttributes(cac, rsi)) {
                return new Applied(true, rsi.getValue());
              }
            } else {
              return new Applied(true, rsi.getValue());
            }
          }

          // this was only a percentage and had no other attributes
          if (rsi.getAttributes().isEmpty()) {
            basePercentage.put(percentageKey, basePercentage.get(percentageKey) + rsi.getPercentage());
          }
        }

        if ((rsi.getPercentage() == null || rsi.getPercentage() == 0) &&
          !rsi.getAttributes().isEmpty()) {
          if (matchAttributes(cac, rsi)) {
            return new Applied(true, rsi.getValue());
          }
        }
      }
    }

    return new Applied(false, null);
  }

  // This applies the rules as an AND. If at any point it fails it jumps out.
  private boolean matchAttributes(ClientContext cac, FeatureRolloutStrategy rsi) {
    for(FeatureRolloutStrategyAttribute attr : rsi.getAttributes()) {
      String suppliedValue = cac.get(attr.getFieldName(), null);

      // "now" for dates and date-times are not passed by the client, so we create them in-situ
      if (suppliedValue == null && "now".equalsIgnoreCase(attr.getFieldName())) {
        if (attr.getType() == RolloutStrategyFieldType.DATE) {
          suppliedValue = DateTimeFormatter.ISO_DATE.format(LocalDateTime.now());
        } else if (attr.getType() == RolloutStrategyFieldType.DATETIME) {
          suppliedValue = DateTimeFormatter.ISO_DATE_TIME.format(LocalDateTime.now());
        }
      }

      Object val = attr.getValues();

      // both are null, just check against equals
      if (val == null && suppliedValue == null) {
        if (attr.getConditional() != RolloutStrategyAttributeConditional.EQUALS) {
          return false;
        }

        continue; // skip this one
      }

      // either of them are null, check against not equals as we can't do anything else
      if (val == null || suppliedValue == null) {
        return false;
      }

      // find the appropriate matcher based on type and match against the supplied value
      if (!matcherRepository.findMatcher(attr).match(suppliedValue, attr)) {
        return false;
      }
    }

    return true;
  }

  private String determinePercentageKey(ClientContext cac, List<String> percentageAttributes) {
    if (percentageAttributes == null || percentageAttributes.isEmpty()) {
      return cac.defaultPercentageKey();
    }

    return percentageAttributes.stream().map(pa -> cac.get(pa, "<none>")).collect(Collectors.joining("$"));
  }


}
