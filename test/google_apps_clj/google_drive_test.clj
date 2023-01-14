(ns google-apps-clj.google-drive-test
  (:require [clojure.test :refer [deftest is testing]]
            [google-apps-clj.google-drive :as sut]
            [google-apps-clj.credentials :as cred]))

(deftest ^:integration test-scenario
  (let [creds (cred/default-credential ["https://www.googleapis.com/auth/drive"])
        q! (partial sut/execute-query! creds)
        folder-name (name (gensym "google-apps-clj-google-drive-"))
        folder (sut/create-folder! creds "root" folder-name)
        folder-id (:id folder)
        upload-content (.getBytes "test-body" "UTF-8")
        upload-request (sut/file-create-query folder-id upload-content "test-title"
                                          {:description "test-description"
                                           :mime-type "text/plain"})]
    (try
      (testing "creates a folder"
        (is folder-id)
        (is (= folder-name (:title folder)))
        (is (sut/folder? folder)))
      (testing "uploads a file"
        (let [file (q! upload-request)
              file-id (:id file)]
          (is file-id)
          (is (= "test-title" (:title file)))
          (is (= "test-description" (:description file)))
          (testing "converts files when possible"
            (is (= "application/vnd.google-apps.document" (:mime-type file))))
          (let [file' (sut/get-file! creds file-id)]
            (is (= "test-title" (:title file')))
            (is (= "test-description" (:description file')))
            (is (= "application/vnd.google-apps.document" (:mime-type file'))))
          (let [files (sut/list-files! creds folder-id)]
            (is (= [file-id] (map :id files))))
          (sut/delete-file! creds file-id)))
      (testing "file permissions"
        (is (= [["owner" "user"]]
               (map (juxt :role :type) (sut/get-permissions! creds folder-id))))
        (let [file-id (:id (q! upload-request))]
          (testing "newly created files have only the owner permission"
            (is (= [["owner" "user"]]
                   (map (juxt :role :type) (sut/get-permissions! creds file-id)))))
          (testing "managing authorization"
            (sut/assign! creds file-id {:principal "dev@sparkfund.co"
                                    :role :reader ;TODO: figure out why Google rejects "reader" role
                                    :searchable? false})
            (is (= [["owner" "user"]
                    ["reader" "group"]]
                   (map (juxt :role :type) (sut/get-permissions! creds file-id))))
            (sut/revoke! creds file-id "dev@sparkfund.co")
            (Thread/sleep 5000)
            (is (= [["owner" "user"]]
                   (map (juxt :role :type) (sut/get-permissions! creds file-id)))))))
      (finally
        (sut/delete-file! creds folder-id)))))
