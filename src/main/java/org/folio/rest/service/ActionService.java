package org.folio.rest.service;

import static io.vertx.core.Future.succeededFuture;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.folio.rest.domain.FeeFineStatus.CLOSED;
import static org.folio.rest.domain.FeeFineStatus.OPEN;
import static org.folio.rest.utils.MonetaryHelper.isZero;
import static org.folio.rest.utils.MonetaryHelper.monetize;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.folio.rest.domain.Action;
import org.folio.rest.jaxrs.model.Account;
import org.folio.rest.jaxrs.model.ActionRequest;
import org.folio.rest.jaxrs.model.Feefineaction;
import org.folio.rest.jaxrs.model.Status;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.repository.AccountRepository;
import org.folio.rest.repository.FeeFineActionRepository;
import org.folio.rest.tools.utils.TenantTool;

import io.vertx.core.Context;
import io.vertx.core.Future;

public class ActionService {
  private final AccountRepository accountRepository;
  private final FeeFineActionRepository feeFineActionRepository;
  private final AccountUpdateService accountUpdateService;
  private final ActionValidationService validationService;
  private final PatronNoticeService patronNoticeService;

  public ActionService(Map<String, String> okapiHeaders, Context vertxContext) {
    PostgresClient postgresClient = PostgresClient.getInstance(vertxContext.owner(),
      TenantTool.tenantId(okapiHeaders));

    this.accountRepository = new AccountRepository(postgresClient);
    this.feeFineActionRepository = new FeeFineActionRepository(postgresClient);
    this.accountUpdateService = new AccountUpdateService(okapiHeaders, vertxContext);
    this.validationService = new ActionValidationService(accountRepository);
    this.patronNoticeService = new PatronNoticeService(vertxContext.owner(), okapiHeaders);
  }

  public Future<ActionContext> pay(String accountId, ActionRequest request) {
    return performAction(Action.PAY, accountId, request);
  }

  private Future<ActionContext> performAction(Action action, String accountId,
    ActionRequest request) {

    return succeededFuture(new ActionContext(action, accountId, request))
      .compose(this::findAccount)
      .compose(this::validateAction)
      .compose(this::createFeeFineAction)
      .compose(this::updateAccount)
      .compose(this::sendPatronNotice);
  }

  private Future<ActionContext> findAccount(ActionContext context) {
    return accountRepository.getAccountById(context.getAccountId())
      .map(context::withAccount);
  }

  private Future<ActionContext> validateAction(ActionContext context) {
    final String amount = context.getRequest().getAmount();

    return validationService.validate(context.getAccount(), amount)
      .map(result -> context.withRequestedAmount(monetize(amount)));
  }

  private Future<ActionContext> createFeeFineAction(ActionContext context) {
    final ActionRequest request = context.getRequest();
    final Account account = context.getAccount();
    final Action action = context.getAction();
    final BigDecimal requestedAmount = context.getRequestedAmount();

    BigDecimal remainingAmountAfterAction = monetize(account.getRemaining())
      .subtract(requestedAmount) ;
    boolean shouldCloseAccount = isZero(remainingAmountAfterAction);
    String actionType = shouldCloseAccount ? action.getFullResult() : action.getPartialResult();

    Feefineaction feeFineAction = new Feefineaction()
      .withAmountAction(requestedAmount.doubleValue())
      .withComments(request.getComments())
      .withNotify(request.getNotifyPatron())
      .withTransactionInformation(request.getTransactionInfo())
      .withCreatedAt(request.getServicePointId())
      .withSource(request.getUserName())
      .withPaymentMethod(request.getPaymentMethod())
      .withAccountId(context.getAccountId())
      .withUserId(account.getUserId())
      .withBalance(remainingAmountAfterAction.doubleValue())
      .withTypeAction(actionType)
      .withId(UUID.randomUUID().toString())
      .withDateAction(new Date())
      .withAccountId(context.getAccountId());

    return feeFineActionRepository.save(feeFineAction)
      .map(context.withFeeFineAction(feeFineAction)
        .withShouldCloseAccount(shouldCloseAccount)
      );
  }

  private Future<ActionContext> updateAccount(ActionContext context) {
    final Feefineaction feeFineAction = context.getFeeFineAction();
    final Account account = context.getAccount();
    final Status accountStatus = account.getStatus();

    account.getPaymentStatus().setName(feeFineAction.getTypeAction());

    if (context.getShouldCloseAccount()) {
      accountStatus.setName(CLOSED.getValue());
      account.setRemaining(monetize(0.0).doubleValue());
    } else {
      accountStatus.setName(OPEN.getValue());
      account.setRemaining(feeFineAction.getBalance());
    }

    return accountUpdateService.updateAccount(account)
      .map(context);
  }

  private Future<ActionContext> sendPatronNotice(ActionContext context) {
    if (isTrue(context.getRequest().getNotifyPatron())) {
      patronNoticeService.sendPatronNotice(context.getFeeFineAction());
    }
    return succeededFuture(context);
  }

  public static class ActionContext {
    private final Action action;
    private final String accountId;
    private final ActionRequest request;
    private BigDecimal requestedAmount;
    private Account account;
    private Feefineaction feeFineAction;
    private boolean shouldCloseAccount;

    public ActionContext(Action action, String accountId, ActionRequest request) {
      this.action = action;
      this.accountId = accountId;
      this.request = request;
    }

    public ActionContext withAccount(Account account) {
      this.account = account;
      return this;
    }

    public ActionContext withFeeFineAction(Feefineaction feefineaction) {
      this.feeFineAction = feefineaction;
      return this;
    }

    public ActionContext withRequestedAmount(BigDecimal requestedAmount) {
      this.requestedAmount = requestedAmount;
      return this;
    }

    public ActionContext withShouldCloseAccount(boolean shouldCloseAccount) {
      this.shouldCloseAccount = shouldCloseAccount;
      return this;
    }

    public String getAccountId() {
      return accountId;
    }

    public ActionRequest getRequest() {
      return request;
    }

    public Action getAction() {
      return action;
    }

    public Account getAccount() {
      return account;
    }

    public Feefineaction getFeeFineAction() {
      return feeFineAction;
    }

    public BigDecimal getRequestedAmount() {
      return requestedAmount;
    }

    public boolean getShouldCloseAccount() {
      return shouldCloseAccount;
    }
  }

}