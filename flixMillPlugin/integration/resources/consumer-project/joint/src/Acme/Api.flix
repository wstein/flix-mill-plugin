mod Acme.Api {
    import com.example.Helper

    @Export
    pub def greet(name: String): String = "Hello, ${name}!"

    // Flix calling Java, in the same module whose facade that Java class calls.
    @Export
    pub def shout(name: String): String \ IO = Helper.shout(name)
}
