-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# No blanket keep for app code: it stopped R8 inlining or removing anything in com.laksh.coffee,
# composable lambdas included. Nothing here is reached by reflection: every serializer is named
# explicitly (kotlinx.serialization ships its own rules), Room ships rules for its generated
# classes, and enum valueOf/values are kept by the default optimize file.
