package com.example;

public final class Helper {

    public static String shout(String s) {
        return s.toUpperCase();
    }

    /** Java calling Flix. */
    public static String viaFlix(String name) {
        return Acme.Api.greet(name);
    }

    /**
     * The facade in a *signature* rather than a body.
     *
     * Nothing calls this. It is here because reading a Java class loads every type named in its
     * signatures, so this is what forces the stubs onto the Flix compile classpath and not only
     * javac's.
     */
    public static Acme.Api passthrough(Acme.Api facade) {
        return facade;
    }
}
