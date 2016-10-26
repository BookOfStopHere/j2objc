/*
 * Copyright 2011 Google Inc. All Rights Reserved.
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
 */

package com.google.devtools.j2objc.translate;

import com.google.common.collect.Lists;
import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.AbstractTypeDeclaration;
import com.google.devtools.j2objc.ast.AnnotationTypeDeclaration;
import com.google.devtools.j2objc.ast.Block;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.ExpressionStatement;
import com.google.devtools.j2objc.ast.FunctionInvocation;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.NativeStatement;
import com.google.devtools.j2objc.ast.PrefixExpression;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.Statement;
import com.google.devtools.j2objc.ast.SuperMethodInvocation;
import com.google.devtools.j2objc.ast.ThisExpression;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.ast.UnitTreeVisitor;
import com.google.devtools.j2objc.ast.VariableDeclarationFragment;
import com.google.devtools.j2objc.jdt.BindingConverter;
import com.google.devtools.j2objc.types.FunctionElement;
import com.google.devtools.j2objc.types.GeneratedExecutableElement;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.NameTable;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

/**
 * Adds release methods to Java classes, in preparation for translation
 * to iOS.  Because Objective-C allows messages to be sent to nil, all
 * fields can be released regardless of whether they currently reference
 * data.
 *
 * @author Tom Ball
 */
public class DestructorGenerator extends UnitTreeVisitor {

  public DestructorGenerator(CompilationUnit unit) {
    super(unit);
  }

  @Override
  public void endVisit(AnnotationTypeDeclaration node) {
    addDeallocMethod(node);
  }

  @Override
  public void endVisit(TypeDeclaration node) {
    if (!node.isInterface()) {
      addDeallocMethod(node);
    }
  }

  private void addDeallocMethod(AbstractTypeDeclaration node) {
    ITypeBinding type = node.getTypeBinding();
    boolean hasFinalize = hasFinalizeMethod(type);
    List<Statement> releaseStatements = createReleaseStatements(node);
    if (releaseStatements.isEmpty() && !hasFinalize) {
      return;
    }

    ExecutableElement deallocElement = GeneratedExecutableElement.newMethodWithSelector(
        NameTable.DEALLOC_METHOD, typeUtil.getVoidType(), BindingConverter.getTypeElement(type))
        .addModifiers(Modifier.PUBLIC);
    MethodDeclaration deallocDecl = new MethodDeclaration(deallocElement);
    deallocDecl.setHasDeclaration(false);
    Block block = new Block();
    deallocDecl.setBody(block);
    List<Statement> stmts = block.getStatements();
    if (hasFinalize) {
      String clsName = nameTable.getFullName(type);
      stmts.add(new NativeStatement("JreCheckFinalize(self, [" + clsName + " class]);"));
    }
    stmts.addAll(releaseStatements);
    if (Options.useReferenceCounting()) {
      stmts.add(new ExpressionStatement(new SuperMethodInvocation(typeEnv.getDeallocMethod())));
    }

    node.addBodyDeclaration(deallocDecl);
  }

  private boolean hasFinalizeMethod(ITypeBinding type) {
    if (type == null || typeEnv.isJavaObjectType(type)) {
      return false;
    }
    for (IMethodBinding method : type.getDeclaredMethods()) {
      if (method.getName().equals(NameTable.FINALIZE_METHOD)
          && method.getParameterTypes().length == 0) {
        return true;
      }
    }
    return hasFinalizeMethod(type.getSuperclass());
  }

  private List<Statement> createReleaseStatements(AbstractTypeDeclaration node) {
    List<Statement> statements = Lists.newArrayList();
    for (VariableDeclarationFragment fragment : TreeUtil.getAllFields(node)) {
      Statement releaseStmt = createRelease(fragment.getVariableBinding());
      if (releaseStmt != null) {
        statements.add(releaseStmt);
      }
    }
    return statements;
  }

  private Statement createRelease(IVariableBinding var) {
    ITypeBinding varType = var.getType();
    if (BindingUtil.isStatic(var) || varType.isPrimitive() || BindingUtil.isWeakReference(var)) {
      return null;
    }
    boolean isVolatile = BindingUtil.isVolatile(var);
    boolean isRetainedWith = BindingUtil.isRetainedWithField(var);
    String funcName = null;
    if (isRetainedWith) {
      funcName = isVolatile ? "JreVolatileRetainedWithRelease" : "JreRetainedWithRelease";
    } else if (isVolatile) {
      funcName = "JreReleaseVolatile";
    } else if (Options.useReferenceCounting()) {
      funcName = "RELEASE_";
    }
    if (funcName == null) {
      return null;
    }
    ITypeBinding voidType = typeEnv.resolveJavaType("void");
    TypeMirror idType = typeEnv.getIdTypeMirror();
    FunctionElement element = new FunctionElement(funcName, voidType, null);
    FunctionInvocation releaseInvocation = new FunctionInvocation(element, voidType);
    if (isRetainedWith) {
      element.addParameters(idType);
      releaseInvocation.addArgument(new ThisExpression(var.getDeclaringClass()));
    }
    element.addParameters(isVolatile ? typeEnv.getPointerType(idType) : idType);
    Expression arg = new SimpleName(var);
    if (isVolatile) {
      arg = new PrefixExpression(
          typeEnv.getPointerType(BindingConverter.getType(varType)),
          PrefixExpression.Operator.ADDRESS_OF, arg);
    }
    releaseInvocation.addArgument(arg);
    return new ExpressionStatement(releaseInvocation);
  }
}
