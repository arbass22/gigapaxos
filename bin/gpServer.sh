#!/bin/bash

HEAD=`dirname $0` 

DEV_MODE=1
if [[ $DEV_MODE == 1 ]]; then
  CLASSPATH=$CLASSPATH:$HEAD/../build/classes
  ENABLE_ASSERTS="-ea"
fi

# Use binaries before jar if available. Convenient to use with
# automatic building in IDEs.
CLASSPATH=$CLASSPATH:.:\
`ls $HEAD/../dist/gigapaxos-[0-9].[0-9].jar`

# default java.util.logging.config.file
LOG_PROPERTIES=logging.properties

# default log4j properties (used by c3p0)
LOG4J_PROPERTIES=log4j.properties

# default gigapaxos properties
GP_PROPERTIES=gigapaxos.properties

# 0 to disable
VERBOSE=2

JAVA=java

ACTIVE="active"
RECONFIGURATOR="reconfigurator"
APPARGS="appArgs"

SSL_OPTIONS="-Djavax.net.ssl.keyStorePassword=qwerty \
-Djavax.net.ssl.keyStore=conf/keyStore/node100.jks \
-Djavax.net.ssl.trustStorePassword=qwerty \
-Djavax.net.ssl.trustStore=conf/keyStore/node100.jks"


# Usage
if [[ -z "$@" || -z `echo "$@"|grep " \(start\|stop\|restart\) "` ]]; then
  echo "Usage: "`dirname $0`/`basename $0`" [JVMARGS] \
[-$APPARGS=\"APPARGS\"]\
 stop|start  all|server_names"
echo "Examples:"
echo "    `dirname $0`/`basename $0` start AR1"
echo "    `dirname $0`/`basename $0` start AR1 AR2 RC1"
echo "    `dirname $0`/`basename $0` start all"
echo "    `dirname $0`/`basename $0` stop AR1 RC1"
echo "    `dirname $0`/`basename $0` stop all"
echo "    `dirname $0`/`basename $0` \
-DgigapaxosConfig=/path/to/gigapaxos.properties start all"
echo "    `dirname $0`/`basename $0` -cp myjars.jar \
-DgigapaxosConfig=/path/to/gigapaxos.properties \
-$APPARGS=\"-opt1=val1 -flag2 \
-str3=\\""\"quoted arg example\\""\" -n 100\" \
 start all" 
fi

# remove classpath
ARGS_EXCEPT_CLASSPATH=`echo "$@"|\
sed -E s/"\-(cp|classpath) [ ]*[^ ]* "/" "/g`

# set JVM args except classpath
JVMARGS="$JVMARGS `echo $ARGS_EXCEPT_CLASSPATH|sed s/-$APPARGS.*$//g|\
sed -E s/" (start|stop) .*$"//g`"

# reconstruct classpath by adding supplied classpath
CLASSPATH="`echo "$@"|grep " *\-\(cp\|classpath\) "|\
sed -E s/"^.* *\-(cp|classpath)  *"//g|\
sed s/" .*$"//g`:$CLASSPATH"


# separate out gigapaxos.properties and appArgs
declare -a args
index=1
start_stop_found=0
for arg in "$@"; do
  if [[ ! -z `echo $arg|grep "\-D.*="` ]]; then
    # JVM args and gigapaxos properties file
    key=`echo $arg|grep "\-D.*="|sed s/-D//g|sed s/=.*//g`
    value=`echo $arg|grep "\-D.*="|sed s/-D//g|sed s/.*=//g`
    if [[ $key == "gigapaxosConfig" ]]; then
      GP_PROPERTIES=$value
    fi
  elif [[ ! -z `echo $arg|grep "\-$APPARGS.*="` ]]; then
    # app args
    APP_ARGS="`echo $arg|grep "\-$APPARGS.*="|sed s/\-$APPARGS=//g`"
  elif [[ $arg == "start" || $arg == "stop" || $arg == "restart" ]]; then
    # server names
    args=(${@:$index})
  fi
  index=`expr $index + 1`
done

# get APPLICATION from gigapaxos.properties file
APP=`grep "^[ \t]*APPLICATION[ \t]*=" $GP_PROPERTIES|sed s/^.*=//g`
  if [[ $APP == "" ]]; then
    # default app
    APP="edu.umass.cs.reconfiguration.examples.noopsimple.NoopApp"
  fi

# Can add more JVM args here. JVM args specified on the command-line
# will override defaults.
DEFAULT_JVMARGS="$ENABLE_ASSERTS -cp $CLASSPATH \
-Djava.util.logging.config.file=$LOG_PROPERTIES \
-Dlog4j.configuration=log4j.properties \
-DgigapaxosConfig=$GP_PROPERTIES"

JVMARGS="$DEFAULT_JVMARGS $JVMARGS"

# set servers to start here
if [[ ${args[1]} == "all" ]]; then

  # get reconfigurators
    reconfigurators=`cat $GP_PROPERTIES|grep "^[ \t]*$RECONFIGURATOR"|\
  sed s/"^.*$RECONFIGURATOR."//g|sed s/"=.*$"//g`
  
  # get actives
	actives=`cat $GP_PROPERTIES|grep "^[ \t]*$ACTIVE"| sed \
s/"^.*$ACTIVE."//g|sed s/"=.*$"//g`
  
  servers="$actives $reconfigurators"
  
else 
  servers="${args[@]:1}"

fi


# start server if local, else append to non_local list
function start_server {
  server=$1
  # for verbose printing
  addressport=`grep "\($ACTIVE\|$RECONFIGURATOR\).$server=" $GP_PROPERTIES|\
    sed s/"^[ \t]*$ACTIVE.$server="//g|\
    sed s/"^[ \t]*$RECONFIGURATOR.$server="//g`
  address=`echo $addressport|sed s/:.*//g`
  if [[ $addressport == "" ]]; then
    non_local="$server $non_local"
    return
  fi
  # check if interface even local
  ifconfig_found=`type ifconfig 2>/dev/null`
  if [[ $ifconfig_found != "" && `ifconfig|grep $address` != "" ]]; then
  if [[ $VERBOSE == 2 ]]; then
    echo "$JAVA $JVMARGS $SSL_OPTIONS \
      edu.umass.cs.reconfiguration.ReconfigurableNode $APP_ARGS $server&"
  fi
  $JAVA $JVMARGS $SSL_OPTIONS \
    edu.umass.cs.reconfiguration.ReconfigurableNode $APP_ARGS $server&
  else
    non_local="$server=$addressport $non_local"
  fi
}

function start_servers {
if [[ $servers != "" ]]; then
  # print app and app args
  if [[ $APP_ARGS != "" ]]; then
    echo "[$APP $APP_ARGS]"
  else
    echo "[$APP]"
  fi
  # start servers
  for server in $servers; do
    start_server $server
  done
  if [[ $non_local != "" && $VERBOSE != 0 ]]; then
    echo "Ignoring non-local server(s) \" $non_local\""
  fi
fi
}

function stop_servers {
  for i in $servers; do
      KILL_TARGET="ReconfigurableNode .*$i"
      pid=`ps -ef|grep "$KILL_TARGET"|grep -v grep|awk '{print $2}' 2>/dev/null`
      if [[ $pid != "" ]]; then
        foundservers="$i($pid) $foundservers"
        pids="$pids $pid"
      fi
  done
  if [[ `echo $pids|sed s/" *"//g` != "" ]]; then
    echo killing $foundservers
    kill -9 $pids 2>/dev/null
  fi
}

case ${args[0]} in

start)
  start_servers
;;

restart)
    stop_servers
    start_servers
;;

stop)
  stop_servers

esac
