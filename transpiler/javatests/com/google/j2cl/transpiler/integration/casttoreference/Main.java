/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.j2cl.transpiler.integration.casttoreference;

public class Main {
  public static void main(String... args) {
    testPrimitiveToReference();
  }

  @SuppressWarnings("cast")
  public static void testPrimitiveToReference() {
    boolean bool = true;
    byte b = 1;
    char c = 'a';
    short s = 1;
    int i = 1;
    long l = 1L;
    float f = 1.0f;
    double d = 1.0;
    Object o = bool;
    assert o != null;
    o = b;
    assert o != null;
    o = c;
    assert o != null;
    o = s;
    assert o != null;
    o = i;
    assert o != null;
    o = l;
    assert o != null;
    o = f;
    assert o != null;
    o = d;
    assert o != null;
    o = (Object) bool;
    assert o != null;
    o = (Object) b;
    assert o != null;
    o = (Object) c;
    assert o != null;
    o = (Object) s;
    assert o != null;
    o = (Object) i;
    assert o != null;
    o = (Object) l;
    assert o != null;
    o = (Object) f;
    assert o != null;
    o = (Object) d;
    assert o != null;
  }
}
