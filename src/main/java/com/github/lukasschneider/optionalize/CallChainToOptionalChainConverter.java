package com.github.lukasschneider.optionalize;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.lang.Language;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PsiJavaPatterns;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import java.util.Optional;

public class CallChainToOptionalChainConverter extends PsiElementBaseIntentionAction {
  @Override
  public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
    if (!(element instanceof PsiExpression)) {
      element = element.getParent();
    }
    List<PsiMethodCallExpression> subsequentExpressions = new ArrayList<>();
    PsiMethodCallExpression innerExpression = null;
    PsiElement currentElement = element;
    while (currentElement instanceof PsiMethodCallExpression || currentElement instanceof PsiReferenceExpression) {
      if (currentElement instanceof PsiMethodCallExpression) {
        if (innerExpression == null) {
          innerExpression = (PsiMethodCallExpression) currentElement;
        } else {
          subsequentExpressions.add((PsiMethodCallExpression) currentElement);
        }
      }
      currentElement = currentElement.getParent();
    }
    if (innerExpression == null) {
      return;
    }

    PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
    JavaCodeStyleManager javaCodeStyleManager = JavaCodeStyleManager.getInstance(project);
    CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);

    PsiMethodCallExpression ofNullableExpression = (PsiMethodCallExpression) factory.createExpressionFromText("Optional.ofNullable(null)", null);
    ofNullableExpression.getArgumentList().getExpressions()[0].replace(innerExpression);
    PsiMethodCallExpression currentMethodCall = ofNullableExpression;
    for (PsiMethodCallExpression originalExpression : subsequentExpressions) {
      PsiMethodCallExpression mapExpression = (PsiMethodCallExpression) factory.createExpressionFromText("opt.map(obj -> obj.call()", null);
      // replace opt
      mapExpression.getMethodExpression().setQualifierExpression(currentMethodCall);
      PsiLambdaExpression lambdaExpression = (PsiLambdaExpression) mapExpression.getArgumentList().getExpressions()[0];
      PsiMethodCallExpression lambdaMethodCall = (PsiMethodCallExpression) lambdaExpression.getBody();
      PsiExpression lambdaMethodCallQualifier = lambdaMethodCall.getMethodExpression().getQualifierExpression();
      lambdaMethodCall.getMethodExpression().replace(originalExpression.getMethodExpression());
      originalExpression.getMethodExpression().setQualifierExpression(lambdaMethodCallQualifier);

      currentMethodCall = mapExpression;
    }

    PsiMethodCallExpression orElseExpression = (PsiMethodCallExpression) factory.createExpressionFromText("opt.orElse(null)", null);
    orElseExpression.getMethodExpression().setQualifierExpression(currentMethodCall);
    javaCodeStyleManager.shortenClassReferences(orElseExpression);
    codeStyleManager.reformat(orElseExpression);
    currentElement.replace(orElseExpression);
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
    if (element.getLanguage() != JavaLanguage.INSTANCE) {
      return false;
    }

    if (element instanceof PsiExpression || element.getParent() instanceof  PsiExpression) {
      return true;
    }
    return false;
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
