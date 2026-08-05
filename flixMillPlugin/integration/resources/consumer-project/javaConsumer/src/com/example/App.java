package com.example;

/** Calls Flix code compiled by the Flix module next door, with no Flix-specific plumbing. */
public final class App {
  public static void main(String[] args) {
    System.out.println("sum = " + Acme.Api.add(2, 40));
  }
}
