# Build

    ant clean && ant dist

    # clot's jar in here
    ls -l dist

    # try it out
    env CLASSPATH=`find $PWD/lib $PWD/dist -name \*.jar | tr '\n' :` \
      java com.draines.clot.main irc.freenode.net 6667 foo '##foo' foopass

