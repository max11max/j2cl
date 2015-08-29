package com.google.j2cl.transpiler.integration.lambdasnestedscope;

/**
 * Test lambda having access to local variables and arguments when placed in mixed scopes.
 * Local class -> local class -> anonymous -> lambda -> anonymous -> lambda
 */
public class MixedNesting {
  class A {
    public void a() {
      int[] x = new int[] {42};
      class B {
        public int b() {
          MyInterface i =
              new MyInterface() {

                @Override
                public int fun(int a) {
                  MyInterface ii =
                      n -> {
                        return new MyInterface() {
                          @Override
                          public int fun(int b) {
                            MyInterface iii = m -> x[0] = x[0] + a + b + n + m;
                            return iii.fun(100);
                          }
                        }.fun(200);
                      };
                  return ii.fun(300);
                }
              };
          return i.fun(400);
        }
      }
      int result = new B().b();
      assert result == 1042;
      assert x[0] == 1042;
    }
  }

  public void test() {
    new A().a();
  }
}
