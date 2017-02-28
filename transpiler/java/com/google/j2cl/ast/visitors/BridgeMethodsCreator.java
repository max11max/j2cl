/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.ast.visitors;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.j2cl.ast.AbstractRewriter;
import com.google.j2cl.ast.AstUtils;
import com.google.j2cl.ast.CastExpression;
import com.google.j2cl.ast.CompilationUnit;
import com.google.j2cl.ast.Expression;
import com.google.j2cl.ast.JsInfo;
import com.google.j2cl.ast.ManglingNameUtils;
import com.google.j2cl.ast.Method;
import com.google.j2cl.ast.MethodCall;
import com.google.j2cl.ast.MethodDescriptor;
import com.google.j2cl.ast.Node;
import com.google.j2cl.ast.SuperReference;
import com.google.j2cl.ast.ThisReference;
import com.google.j2cl.ast.Type;
import com.google.j2cl.ast.TypeDeclaration;
import com.google.j2cl.ast.TypeDescriptor;
import com.google.j2cl.ast.Variable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks circumstances where a bridge method should be generated and creates the bridge methods.
 */
public class BridgeMethodsCreator extends NormalizationPass {
  @Override
  public void applyTo(CompilationUnit compilationUnit) {
    for (Type type : compilationUnit.getTypes()) {
      type.addMethods(createBridgeMethods(type));
    }
  }

  /** Creates and adds bridge methods to the Java type and fixes the target JS methods. */
  private static Collection<Method> createBridgeMethods(Type type) {
    Set<String> usedMangledNames = new HashSet<>();
    Multimap<MethodDescriptor, Method> bridgeMethodsByTargetMethodDescriptor =
        LinkedHashMultimap.create();

    // Plan bridge methods.
    Map<MethodDescriptor, MethodDescriptor> targetMethodDescriptorsByBridgeMethodDescriptor =
        findTargetMethodDescriptorsByBridgeMethodDescriptor(type.getDeclaration());

    // Create bridge methods.
    for (Map.Entry<MethodDescriptor, MethodDescriptor> entry :
        targetMethodDescriptorsByBridgeMethodDescriptor.entrySet()) {
      MethodDescriptor bridgeMethodDescriptor = entry.getKey();
      MethodDescriptor targetMethodDescriptor = entry.getValue();

      Method bridgeMethod =
          createBridgeMethod(type.getDeclaration(), bridgeMethodDescriptor, targetMethodDescriptor);

      if (!usedMangledNames.add(ManglingNameUtils.getMangledName(bridgeMethod.getDescriptor()))) {
        // Do not generate duplicate bridge methods in one class.
        continue;
      }

      bridgeMethodsByTargetMethodDescriptor.put(
          targetMethodDescriptor.getDeclarationMethodDescriptor(), bridgeMethod);
    }

    fixJsInfo(type, bridgeMethodsByTargetMethodDescriptor);
    return bridgeMethodsByTargetMethodDescriptor.values();
  }

  private static void fixJsInfo(
      Type type, final Multimap<MethodDescriptor, Method> bridgeMethodsByTargetMethodDescriptor) {
    type.accept(
        new AbstractRewriter() {
          @Override
          public Node rewriteMethod(Method method) {
            Collection<Method> bridgeJsMethods =
                bridgeMethodsByTargetMethodDescriptor
                    .get(method.getDescriptor())
                    .stream()
                    .filter(bridgeMethod -> bridgeMethod.getDescriptor().isJsMethod())
                    .collect(Collectors.toList());
            if (bridgeJsMethods.isEmpty()) {
              return method;
            }

            for (Method bridgeJsMethod : bridgeJsMethods) {
              // Transfer parameter optionality from the target methods which will be "demoted"
              // to non JsMethod.
              // TODO(rluble): parameter optionality might better be moved to MethodDescriptor
              // from method and all this business of moving optionality would have been
              // handled in a simpler manner.
              for (int i = 0; i < method.getParameters().size(); i++) {
                bridgeJsMethod.setParameterOptionality(i, method.isParameterOptional(i));
              }
            }
            // Now that the bridge method is created (and marked JsMethod), "demote" to a plain
            // Java method by setting JsInfo to NONE and resetting parameter optionality.
            Method.Builder methodBuilder = Method.Builder.from(method).setJsInfo(JsInfo.NONE);
            for (int i = 0; i < method.getParameters().size(); i++) {
              methodBuilder.setParameterOptional(i, false);
            }
            return methodBuilder.build();
          }
        });
  }

  /** Returns the mappings from the needed bridge method to the targeted method. */
  private static Map<MethodDescriptor, MethodDescriptor>
      findTargetMethodDescriptorsByBridgeMethodDescriptor(TypeDeclaration typeDeclaration) {
    // Do not create bridge methods in interfaces.
    if (typeDeclaration.isInterface()) {
      return ImmutableMap.of();
    }

    Map<MethodDescriptor, MethodDescriptor> targetMethodDescriptorByBridgeMethodDescriptor =
        new LinkedHashMap<>();

    for (MethodDescriptor bridgeMethodDescriptor :
        getPotentialBridgeMethodDescriptors(typeDeclaration.getUnsafeTypeDescriptor())) {
      // Attempt to target a concrete method.
      MethodDescriptor targetMethodDescriptor =
          findForwardingMethodDescriptor(
              bridgeMethodDescriptor, typeDeclaration.getUnsafeTypeDescriptor());
      if (targetMethodDescriptor != null) {
        targetMethodDescriptorByBridgeMethodDescriptor.put(
            bridgeMethodDescriptor, targetMethodDescriptor);
        continue;
      }

      // Failing that, attempt to target an accidental override, but of course ensure that the
      // target is concrete.
      if (!bridgeMethodDescriptor.isAbstract()) {
        MethodDescriptor backwardingMethodDescriptor =
            findBackwardingMethodDescriptor(bridgeMethodDescriptor, typeDeclaration);
        if (backwardingMethodDescriptor != null) {
          targetMethodDescriptorByBridgeMethodDescriptor.put(
              backwardingMethodDescriptor, bridgeMethodDescriptor);
          continue;
        }
      }
    }
    return targetMethodDescriptorByBridgeMethodDescriptor;
  }

  /**
   * Returns all the potential methods in the super classes and super interfaces that may need a
   * bridge method generating in {@code type}.
   *
   * <p>A bridge method is needed in a type when the type extends or implements a parameterized
   * class or interface and type erasure changes the signature of any inherited method. This
   * inherited method is a potential method that may need a bridge method.
   */
  private static List<MethodDescriptor> getPotentialBridgeMethodDescriptors(
      TypeDescriptor typeDescriptor) {
    List<MethodDescriptor> potentialBridgeMethodDescriptors = new ArrayList<>();
    TypeDescriptor superTypeDescriptor = typeDescriptor.getSuperTypeDescriptor();
    if (superTypeDescriptor != null) {
      // add the potential bridge methods from direct super class.
      potentialBridgeMethodDescriptors.addAll(
          getPotentialBridgeMethodDescriptorsInType(superTypeDescriptor));
      // recurse into super class.
      potentialBridgeMethodDescriptors.addAll(
          getPotentialBridgeMethodDescriptors(superTypeDescriptor));
    }
    for (TypeDescriptor superInterface : typeDescriptor.getInterfaceTypeDescriptors()) {
      // add the potential bridge methods from direct super interfaces.
      potentialBridgeMethodDescriptors.addAll(
          getPotentialBridgeMethodDescriptorsInType(superInterface));
      // recurse into super interfaces.
      potentialBridgeMethodDescriptors.addAll(getPotentialBridgeMethodDescriptors(superInterface));
    }
    return potentialBridgeMethodDescriptors;
  }

  /** Returns the potential methods in {@code type} that may need a bridge method. */
  private static Collection<MethodDescriptor> getPotentialBridgeMethodDescriptorsInType(
      TypeDescriptor typeDescriptor) {
    return Collections2.filter(
        typeDescriptor.getDeclaredMethodDescriptors(),
        /**
         * If {@code method}, the method with more specific type arguments, has different method
         * signature with {@code method.getMethodDeclaration()}, the original generic method, it
         * means this method is a potential method that may need a bridge method.
         */
        methodDescriptor ->
            !methodDescriptor.isConstructor()
                && !methodDescriptor.isSynthetic()
                // is a parameterized method.
                && methodDescriptor != methodDescriptor.getDeclarationMethodDescriptor()
                // type erasure changes the signature
                && !methodDescriptor.isJsOverride(
                    methodDescriptor.getDeclarationMethodDescriptor()));
  }

  /**
   * Returns the targeted method (implemented or inherited) by {@code type} that {@code
   * bridgeMethod} should be targeted to.
   *
   * <p>If a method (a method with more specific type arguments) in {@code type} or in its super
   * types has the same erasured signature with {@code bridgeMethod}, it is an overriding method for
   * {@code bridgeMethod}. And if their original method declarations are different then a bridge
   * method is needed to make overriding work.
   */
  private static MethodDescriptor findForwardingMethodDescriptor(
      MethodDescriptor bridgeMethodDescriptor, TypeDescriptor typeDescriptor) {
    for (MethodDescriptor declaredMethodDescriptor :
        typeDescriptor.getDeclaredMethodDescriptors()) {
      if (!declaredMethodDescriptor.equals(bridgeMethodDescriptor) // should not target itself
          && !declaredMethodDescriptor.isAbstract() // should be a concrete implementation.
          // concrete methods have the same signature, thus an overriding.
          && declaredMethodDescriptor.isJsOverride(bridgeMethodDescriptor)
          // original method declarations have different signatures
          && !declaredMethodDescriptor
              .getDeclarationMethodDescriptor()
              .isJsOverride(bridgeMethodDescriptor.getDeclarationMethodDescriptor())) {
        // find a overriding method (also possible accidental overriding), this is the method that
        // should be targeted.
        return declaredMethodDescriptor;
      }
    }

    // recurse to super class.
    if (typeDescriptor.getSuperTypeDescriptor() != null) {
      MethodDescriptor targetMethodDescriptor =
          findForwardingMethodDescriptor(
              bridgeMethodDescriptor, typeDescriptor.getSuperTypeDescriptor());
      if (targetMethodDescriptor != null) {
        return targetMethodDescriptor;
      }
    }

    // recurse to super interfaces.
    for (TypeDescriptor interfaceTypeDescriptor : typeDescriptor.getInterfaceTypeDescriptors()) {
      MethodDescriptor targetMethodDescriptor =
          findForwardingMethodDescriptor(bridgeMethodDescriptor, interfaceTypeDescriptor);
      if (targetMethodDescriptor != null) {
        return targetMethodDescriptor;
      }
    }

    return null;
  }

  /**
   * Returns the method in its super interface that needs a bridge method delegating to {@code
   * bridgeMethod}.
   *
   * <p>If a method in the super interfaces of {@code type} is a method with more specific type
   * arguments, and it is overridden by a generic method, it needs a bridge method that delegates to
   * the generic method.
   */
  private static MethodDescriptor findBackwardingMethodDescriptor(
      MethodDescriptor bridgeMethodDescriptor, TypeDeclaration typeDeclaration) {
    for (TypeDescriptor superInterface : typeDeclaration.getTransitiveInterfaceTypeDescriptors()) {
      for (MethodDescriptor methodDescriptor : superInterface.getDeclaredMethodDescriptors()) {
        if (methodDescriptor
                == methodDescriptor.getDeclarationMethodDescriptor() // non-generic method,
            // generic method has been investigated by findForwardingMethod.
            && methodDescriptor.isJsOverride(bridgeMethodDescriptor)
            // is overridden by a generic method with different erasure parameter types.
            && !methodDescriptor.isJsOverride(
                bridgeMethodDescriptor.getDeclarationMethodDescriptor())) {
          return methodDescriptor;
        }
      }
    }
    return null;
  }

  /**
   * Creates MethodDescriptor in {@code type} that has the same signature as {@code
   * methodDescriptor} with return type of {@code returnType}.
   */
  private static MethodDescriptor createMethodDescriptor(
      TypeDeclaration typeDeclaration,
      MethodDescriptor originalMethodDescriptor,
      TypeDescriptor returnTypeDescriptor) {
    checkArgument(!typeDeclaration.isInterface());

    return MethodDescriptor.Builder.from(originalMethodDescriptor)
        .setEnclosingClassTypeDescriptor(typeDeclaration.getUnsafeTypeDescriptor())
        .setReturnTypeDescriptor(returnTypeDescriptor)
        .build();
  }

  /**
   * Returns bridge method that calls the targeted method in its body.
   *
   * <p>bridgeMethod: parameterized method with more specific type arguments.
   * bridgeMethod.getMethodDeclaration(): original declaration method. targetMethod: concrete
   * implementation that should be targeted.
   */
  private static Method createBridgeMethod(
      TypeDeclaration typeDeclaration,
      MethodDescriptor bridgeMethodDescriptor,
      MethodDescriptor targetMethodDescriptor) {
    MethodDescriptor originalBridgeMethodDescriptor = bridgeMethodDescriptor;

    bridgeMethodDescriptor =
        tweakBridgeSignature(typeDeclaration, bridgeMethodDescriptor, targetMethodDescriptor);
    targetMethodDescriptor = tweakTargetSignature(bridgeMethodDescriptor, targetMethodDescriptor);

    List<Variable> parameters = new ArrayList<>();
    List<Expression> arguments = new ArrayList<>();

    MethodDescriptor declarationBridgeMethodDescriptor =
        bridgeMethodDescriptor.getDeclarationMethodDescriptor();
    for (int i = 0; i < bridgeMethodDescriptor.getParameterTypeDescriptors().size(); i++) {
      Variable parameter =
          Variable.newBuilder()
              .setName("arg" + i)
              .setTypeDescriptor(bridgeMethodDescriptor.getParameterTypeDescriptors().get(i))
              .setIsParameter(true)
              .build();

      parameters.add(parameter);
      Expression parameterReference = parameter.getReference();

      // The type the argument should be casted to. It should be casted to the specific parameter
      // type that is expected by the concrete parameterized method.
      TypeDescriptor castToParameterTypeDescriptor =
          originalBridgeMethodDescriptor.getParameterTypeDescriptors().get(i);
      // if the parameter type in bridge method is different from that in parameterized method,
      // add a cast.
      Expression argument =
          declarationBridgeMethodDescriptor
                  .getParameterTypeDescriptors()
                  .get(i)
                  .hasSameRawType(castToParameterTypeDescriptor)
              ? parameterReference
              : CastExpression.newBuilder()
                  .setExpression(parameterReference)
                  .setCastTypeDescriptor(castToParameterTypeDescriptor)
                  .build();
      arguments.add(argument);
    }
    TypeDescriptor targetEnclosingClassTypeDescriptor =
        targetMethodDescriptor.getEnclosingClassTypeDescriptor();
    Expression qualifier =
        bridgeMethodDescriptor.isMemberOf(targetEnclosingClassTypeDescriptor)
                || targetEnclosingClassTypeDescriptor.isInterface()
            ? new ThisReference(targetEnclosingClassTypeDescriptor)
            : new SuperReference(targetEnclosingClassTypeDescriptor);

    Expression dispatchMethodCall =
        MethodCall.Builder.from(targetMethodDescriptor)
            .setQualifier(qualifier)
            .setArguments(arguments)
            .build();

    checkArgument(bridgeMethodDescriptor.isSynthetic());
    checkArgument(bridgeMethodDescriptor.isBridge());
    return Method.newBuilder()
        .setMethodDescriptor(bridgeMethodDescriptor)
        .setParameters(parameters)
        .addStatements(
            AstUtils.createReturnOrExpressionStatement(
                dispatchMethodCall, bridgeMethodDescriptor.getReturnTypeDescriptor()))
        .setIsOverride(true)
        .setJsDocDescription("Bridge method.")
        .build();
  }

  private static MethodDescriptor tweakTargetSignature(
      MethodDescriptor bridgeMethodDescriptor, MethodDescriptor targetMethodDescriptor) {
    // The MethodDescriptor of the targeted method.
    JsInfo targetMethodJsInfo = targetMethodDescriptor.getJsInfo();

    // If a JsFunction method needs a bridge, only the bridge method is a JsFunction method, and it
    // targets to *real* implementation, which is not a JsFunction method.
    // If both a method and the bridge method are JsMethod, only the bridge method is a JsMethod,
    // and it targets the *real* implementation, which should be emit as non-JsMethod.
    if (bridgeMethodDescriptor.isJsMethod()
        && targetMethodDescriptor.inSameTypeAs(bridgeMethodDescriptor)) {
      targetMethodJsInfo = JsInfo.NONE;
    }
    return MethodDescriptor.Builder.from(targetMethodDescriptor)
        .setJsInfo(targetMethodJsInfo)
        .setJsFunction(false)
        .build();
  }

  private static MethodDescriptor tweakBridgeSignature(
      TypeDeclaration typeDeclaration,
      MethodDescriptor bridgeMethodDescriptor,
      MethodDescriptor targetMethodDescriptor) {
    // The MethodDescriptor of the generated bridge method should have the same signature as the
    // original declared method.
    // Using the return type of the targeted method also avoids generating redundant bridge methods
    // for two methods that have the same parameter signature but different return types.
    TypeDescriptor returnTypeDescriptor =
        bridgeMethodDescriptor == bridgeMethodDescriptor.getDeclarationMethodDescriptor()
            ? bridgeMethodDescriptor
                .getReturnTypeDescriptor() // use its own return type if it is a concrete method
            : targetMethodDescriptor
                .getReturnTypeDescriptor(); // otherwise use the return type of the target method.
    bridgeMethodDescriptor =
        MethodDescriptor.Builder.from(
                createMethodDescriptor(
                    typeDeclaration, bridgeMethodDescriptor, returnTypeDescriptor))
            .setParameterTypeDescriptors(
                bridgeMethodDescriptor
                    .getDeclarationMethodDescriptor()
                    .getParameterTypeDescriptors()
                    .stream()
                    .map(p -> p.getRawTypeDescriptor())
                    .toArray(TypeDescriptor[]::new))
            .setSynthetic(true)
            .setBridge(true)
            .setAbstract(false)
            .setNative(false)
            .build();
    return bridgeMethodDescriptor;
  }
}
