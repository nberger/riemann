(ns riemann.test.transport
  (:use riemann.common
        riemann.core
        riemann.transport.tcp
        riemann.transport.udp
        riemann.transport.websockets
        riemann.logging
        clojure.test
        lamina.core
        aleph.tcp
        aleph.udp
        gloss.core)
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [riemann.client :as client]
            [riemann.index :as index])
  (:import (org.jboss.netty.buffer ChannelBuffers)
           (java.io IOException)))

(riemann.logging/init)

(deftest ws-put-events-test
         (riemann.logging/suppress
           ["riemann.transport"
            "riemann.core"
            "riemann.pubsub"]
           (let [server (ws-server)
                 uri    "http://127.0.0.1:5556/events"
                 core   (transition! (core) {:services [server]})]
             ; Two simple events
             (let [res (http/put uri
                                 {:body "{\"service\": \"foo\"}\n{\"service\": \"bar\"}\n"})]
               (is (= 200 (:status res)))
               (is (= "{}\n{}\n" (:body res))))

             ; A time
             (let [res (http/put uri
                                 {:body "{\"service\": \"foo\", \"time\": \"2013-04-15T18:06:58-07:00\"}\n"})]
               (is (= 200 (:status res)))
               (is (= "{}\n" (:body res))))

             ; An invalid time
             (let [res (http/put uri
                                 {:body "{\"time\": \"xkcd\"}"})]
               (is (= 200 (:status res)))
               (is (= {:error "Invalid format: \"xkcd\""}
                      (json/parse-string (:body res) true))))

             (stop! core))))

(deftest udp
         (riemann.logging/suppress ["riemann.transport"
                                    "riemann.core"
                                    "riemann.pubsub"]
           (let [server (udp-server)
                 core   (transition! (core) {:services [server]})
                 client (wait-for-result (udp-socket {}))
                 msg (ChannelBuffers/wrappedBuffer
                       (encode {:ok true}))]

             (try
               (enqueue client {:host "localhost" 
                                :port 5555 
                                :message msg})
               (Thread/sleep 100)
               (finally
                 (close client)
                 (stop! core))))))

(defn test-tcp-client
  [client-opts server-opts]
  (riemann.logging/suppress ["riemann.transport"
                             "riemann.core"
                             "riemann.pubsub"]
    (let [server (tcp-server server-opts)
          index (index/index)
          core (transition! (core) {:index index
                                    :services [server]
                                    :streams [(partial index/update index)]})]
      (try
        (let [client (apply client/tcp-client (mapcat identity client-opts))]
          (try
            (client/send-event client {:service "laserkat"})
            (is (= "laserkat" (-> client
                                (client/query "service = \"laserkat\"")
                                first
                                :service)))
            (finally
              (client/close-client client))))
        (finally
          (stop! core))))))

(deftest tls-test
         (let [server {:tls? true
                       :key "test/data/tls/server.pkcs8"
                       :cert "test/data/tls/server.crt"
                       :ca-cert "test/data/tls/demoCA/cacert.pem"}
               client {:tls? true
                       :key "test/data/tls/client.pkcs8"
                       :cert "test/data/tls/client.crt"
                       :ca-cert "test/data/tls/demoCA/cacert.pem"}]
           ; Works with valid config
           (test-tcp-client client server)

           ; Fails with mismatching client key/cert
           (is (thrown? IOException
                        (test-tcp-client (assoc client :key (:key server))
                                         server)))
           ; Fails with non-CA client CA cert
           (is (thrown? IOException
                        (test-tcp-client (assoc client :ca-cert (:cert client))
                                         server)))
           ; Fails with mismatching server key/cert
           (is (thrown? IOException
                        (test-tcp-client client
                                         (assoc server :key (:key client)))))
           ; Fails with non-CA server CA cert
           (is (thrown? IOException
                        (test-tcp-client client
                                         (assoc server :ca-cert
                                                (:cert client)))))))

(deftest ignores-garbage
         (riemann.logging/suppress ["riemann.transport"
                                    "riemann.core"
                                    "riemann.pubsub"]
            (let [server (tcp-server)
                  core   (transition! (core) {:services [server]})
                  client (wait-for-result 
                           (aleph.tcp/tcp-client 
                             {:host "localhost" 
                              :port 5555
                              :frame (finite-block :int32)}))]

              (try
                (enqueue client 
                         (java.nio.ByteBuffer/wrap 
                           (byte-array (map byte [0 1 2]))))
                (is (thrown? java.lang.IllegalStateException
                             (wait-for-message client)))
                (is (closed? client))
                (finally
                  (close client)
                  (stop! core))))))
