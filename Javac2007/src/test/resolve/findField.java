package test.enter;


class A {
	int a;
}
interface B {
	int a=0;
	int b=0;
}
interface C {
	int a=0;
	int b=0;
}

class D extends A implements B,C {
	int i=a;
	int j=b;
}
