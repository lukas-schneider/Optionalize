package com.github.lukasschneider.optionalize;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInspection.LambdaCanBeMethodReferenceInspection;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.codeInspection.util.IntentionName;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@NonNls
public class CallChainToOptionalChainConverter extends PsiElementBaseIntentionAction {

  private static class CallChainContext {

    private final PsiExpression innermostExpression;
    private final List<PsiMethodCallExpression> callChain;
    private final PsiMethodCallExpression outermostCall;

    public CallChainContext(PsiExpression innermostExpression, List<PsiMethodCallExpression> callChain, PsiMethodCallExpression outermostCall) {
      this.innermostExpression = innermostExpression;
      this.callChain = callChain;
      this.outermostCall = outermostCall;
    }

    public PsiExpression getInnermostExpression() {
      return innermostExpression;
    }

    public List<PsiMethodCallExpression> getCallChain() {
      return callChain;
    }

    public PsiMethodCallExpression getOutermostCall() {
      return outermostCall;
    }
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
    CallChainContext context = getCallChainContext(element);
    if (context == null) return;

    PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
    JavaCodeStyleManager javaCodeStyleManager = JavaCodeStyleManager.getInstance(project);
    CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);

    String optionalText = getOptionalText(context.getInnermostExpression());
    PsiMethodCallExpression ofNullableExpression = (PsiMethodCallExpression) factory.createExpressionFromText(optionalText, null);
    ofNullableExpression.getArgumentList().getExpressions()[0].replace(context.getInnermostExpression());
    PsiMethodCallExpression currentMethodCall = ofNullableExpression;
    for (PsiMethodCallExpression originalMethodCall : context.getCallChain()) {
      PsiMethodCallExpression mapExpression = (PsiMethodCallExpression) factory.createExpressionFromText("opt.map(obj -> null)", null);
      if (originalMethodCall.getArgumentList().isEmpty()) {
        originalMethodCall.getMethodExpression().setQualifierExpression(factory.createExpressionFromText("obj", null));
      } else {
        originalMethodCall.getArgumentList().getExpressions()[0].replace(factory.createExpressionFromText("obj", null));
      }
      // replace opt
      mapExpression.getMethodExpression().setQualifierExpression(currentMethodCall);
      PsiLambdaExpression lambdaExpression = (PsiLambdaExpression) mapExpression.getArgumentList().getExpressions()[0];
      Objects.requireNonNull(lambdaExpression.getBody());
      lambdaExpression.getBody().replace(originalMethodCall);
      currentMethodCall = mapExpression;
    }

    PsiMethodCallExpression orElseExpression = (PsiMethodCallExpression) factory.createExpressionFromText("opt.orElse(null)", null);
    orElseExpression.getMethodExpression().setQualifierExpression(currentMethodCall);
    PsiElement newElement = context.getOutermostCall().replace(orElseExpression);
    LambdaCanBeMethodReferenceInspection.replaceAllLambdasWithMethodReferences(newElement);
    javaCodeStyleManager.shortenClassReferences(newElement);
    codeStyleManager.reformat(newElement);
  }

  @NotNull
  private String getOptionalText(PsiExpression expression) {
    if (expression instanceof PsiMethodCallExpression) {
      PsiMethod method = ((PsiMethodCallExpression) expression).resolveMethod();
      if (method != null && AnnotationUtil.isAnnotated(method, Collections.singletonList(AnnotationUtil.NOT_NULL), AnnotationUtil.CHECK_INFERRED)) {
        return "java.util.Optional.of(null)";
      }
    }
    return "java.util.Optional.ofNullable(null)";
  }

  @Nullable
  private CallChainContext getCallChainContext(@NotNull PsiElement element) {
    PsiMethodCallExpression parentCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
    PsiExpression qualifier = Optional.ofNullable(parentCall).map(PsiMethodCallExpression::getMethodExpression).map(PsiReferenceExpression::getQualifierExpression).orElse(null);

    PsiExpression firstExpression;
    if (element instanceof PsiJavaToken && ((PsiJavaToken) element).getTokenType() == JavaTokenType.DOT) {
      PsiElement elementToUse = ObjectUtils.coalesce(element.getPrevSibling(), element.getParent());
      if (!(elementToUse instanceof PsiExpression)) {
        return null;
      }
      firstExpression = (PsiExpression) elementToUse;
    } else if (element instanceof PsiMethodCallExpression) {
      firstExpression = (PsiExpression) element;
    } else if (PsiTreeUtil.isAncestor(qualifier, element, false)) {
      firstExpression = qualifier;
    } else if (parentCall != null) {
      firstExpression = parentCall;
    } else {
      return null;
    }

    PsiElement currentElement = firstExpression;
    List<PsiMethodCallExpression> callChain = new ArrayList<>();
    PsiMethodCallExpression lastCall = null;
    while (currentElement instanceof PsiExpression || currentElement instanceof PsiExpressionList) {
      if (currentElement instanceof PsiMethodCallExpression && !firstExpression.equals(currentElement)) {
        callChain.add((PsiMethodCallExpression) currentElement);
        lastCall = (PsiMethodCallExpression) currentElement;
      }
      currentElement = currentElement.getParent();
    }

    if (callChain.isEmpty()) {
      return null;
    }

    return new CallChainContext(firstExpression, callChain, lastCall);
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
    if (element.getLanguage() != JavaLanguage.INSTANCE) {
      return false;
    }

    return getCallChainContext(element) != null;
  }

  @Override
  public @IntentionName @NotNull String getText() {
    return "Replace with null-safe Optional chain";
  }

  @Override
  public @NotNull @IntentionFamilyName String getFamilyName() {
    return "Optional";
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }
}
