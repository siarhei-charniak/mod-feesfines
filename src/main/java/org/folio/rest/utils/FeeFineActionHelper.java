package org.folio.rest.utils;

import java.util.Arrays;

import org.folio.rest.domain.Action;
import org.folio.rest.jaxrs.model.Feefineaction;

public class FeeFineActionHelper {
    private FeeFineActionHelper() {
    throw new UnsupportedOperationException("Do not instantiate");
  }

  public static boolean isCharge(Feefineaction action) {
    return !isAction(action) && action.getPaymentMethod() == null;
  }

  public static boolean isAction(Feefineaction action) {
    return Action.isActionResult(action.getTypeAction());
  }

  public static boolean isActionOfType(Feefineaction feefineaction, Action... actions) {
    return Arrays.stream(actions)
      .anyMatch(action -> action.isActionForResult(feefineaction.getTypeAction()));
  }
}
