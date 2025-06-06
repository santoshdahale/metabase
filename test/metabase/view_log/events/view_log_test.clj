(ns metabase.view-log.events.view-log-test
  (:require
   [clojure.test :refer :all]
   [java-time.api :as t]
   [metabase.dashboards.api-test :as api.dashboard-test]
   [metabase.embedding.api.embed-test :as embed-test]
   [metabase.events.core :as events]
   [metabase.permissions.models.data-permissions :as data-perms]
   [metabase.permissions.models.permissions-group :as perms-group]
   [metabase.public-sharing.api-test :as public-test]
   [metabase.test :as mt]
   [metabase.test.http-client :as client]
   [metabase.util :as u]
   [metabase.view-log.events.view-log :as events.view-log]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn latest-view
  "Returns the most recent view for a given user and model ID"
  [user-id model-id]
  (t2/select-one :model/ViewLog
                 :user_id user-id
                 :model_id model-id
                 {:order-by [[:id :desc]]}))

(deftest card-read-ee-test
  (mt/with-premium-features #{:audit-app}
    (mt/with-temp [:model/User user {}
                   :model/Card card {:creator_id (u/id user)}]
      (testing "A basic card read event is recorded in EE"
        (events/publish-event! :event/card-read {:object-id (u/id card) :user-id (u/the-id user), :context :question})
        (is (partial=
             {:user_id  (u/id user)
              :model    "card"
              :model_id (u/id card)
              :has_access true
              :context    :question}
             (latest-view (u/id user) (u/id card))))))))

(deftest card-read-oss-no-view-logging-test
  (mt/with-premium-features #{}
    (mt/with-temp [:model/User user {}
                   :model/Card card {:creator_id (u/id user)}]
      (testing "A basic card read event is not recorded in OSS"
        (events/publish-event! :event/card-read {:object-id (u/id card) :user-id (u/the-id user) :context :question})
        (is (nil? (latest-view (u/id user) (u/id card)))
            "view log entries should not be made in OSS")))))

(deftest collection-read-ee-test
  (mt/with-premium-features #{:audit-app}
    (mt/with-temp [:model/Collection coll {}]
      (testing "A basic collection read event is recorded in EE"
        (events/publish-event! :event/collection-read {:object coll :user-id (mt/user->id :crowberto)})
        (is (partial=
             {:user_id    (mt/user->id :crowberto)
              :model      "collection"
              :model_id   (u/id coll)
              :has_access true
              :context    nil}
             (latest-view (mt/user->id :crowberto) (u/id coll))))))))

(deftest update-view-dashboard-timestamp-test
  ;; the DB might save `last_used_at` with a different level of precision than the JVM does, on some machines
  ;; `offset-date-time` returns nanosecond precision (9 decimal places) but `last_viewed_at` is coming back with
  ;; microsecond precision (6 decimal places). We don't care about such a small difference, just strip it off of the
  ;; times we're comparing.
  (let [now           (-> (t/offset-date-time)
                          (.withNano 0))
        one-hour-ago  (t/minus now (t/hours 1))
        two-hours-ago (t/minus now (t/hours 2))]
    (testing "update with multiple dashboards of the same ids will set timestamp to the latest"
      (mt/with-temp
        [:model/Dashboard {dashboard-id-1 :id} {:last_viewed_at two-hours-ago}]
        (#'events.view-log/update-dashboard-last-viewed-at!* [{:id dashboard-id-1 :timestamp one-hour-ago}
                                                              {:id dashboard-id-1 :timestamp two-hours-ago}])
        (is (= one-hour-ago
               (-> (t2/select-one-fn :last_viewed_at :model/Dashboard dashboard-id-1)
                   t/offset-date-time
                   (.withNano 0))))))

    (testing "if the existing last_viewed_at is greater than the updating values, do not override it"
      (mt/with-temp
        [:model/Dashboard {dashboard-id-2 :id} {:last_viewed_at now}]
        (#'events.view-log/update-dashboard-last-viewed-at!* [{:id dashboard-id-2 :timestamp one-hour-ago}])
        (is (= now
               (-> (t2/select-one-fn :last_viewed_at :model/Dashboard dashboard-id-2)
                   t/offset-date-time
                   (.withNano 0))))))))

(deftest table-read-ee-test
  (mt/with-premium-features #{:audit-app}
    (mt/with-temp [:model/User user {}]
      (let [table (t2/select-one :model/Table :id (mt/id :users))]
        (testing "A basic table read event is recorded in EE"
          (events/publish-event! :event/table-read {:object table :user-id (u/id user)})
          (is (partial=
               {:user_id  (u/id user)
                :model    "table"
                :model_id (u/id table)
                :has_access nil
                :context    nil}
               (latest-view (u/id user) (u/id table)))))

        (testing "If a user is bound, has_access is recorded in EE based on the user's current permissions"
          (mt/with-full-data-perms-for-all-users!
            (mt/with-current-user (u/id user)
              (events/publish-event! :event/table-read {:object table :user-id (u/id user)})
              (is (true? (:has_access (latest-view (u/id user) (u/id table))))))

            ;; Bind the user again to flush the perms cache
            (mt/with-current-user (u/id user)
              (data-perms/set-table-permission! (perms-group/all-users) (mt/id :users) :perms/create-queries :no)
              (events/publish-event! :event/table-read {:object table :user-id (u/id user)})
              (is (false? (:has_access (latest-view (u/id user) (u/id table))))))))))))

(deftest dashboard-read-ee-test
  (mt/with-premium-features #{:audit-app}
    (mt/with-temp [:model/User          user      {}
                   :model/Dashboard     dashboard {:name "Test Dashboard"}
                   :model/Card          card      {:name "Dashboard Test Card"}
                   :model/DashboardCard _dashcard {:dashboard_id (u/id dashboard) :card_id (u/id card)}]
      (let [dashboard (t2/hydrate dashboard [:dashcards :card])]
        (testing "A basic dashboard read event is recorded in EE"
          (events/publish-event! :event/dashboard-read {:object-id (:id dashboard) :user-id (u/id user)})
          (is (partial=
               {:user_id    (u/id user)
                :model      "dashboard"
                :model_id   (u/id dashboard)
                :has_access true
                :context    nil}
               (latest-view (u/id user) (u/id dashboard))))
          (testing "Card read events are not recorded when viewing a dashboard"
            (is (nil? (latest-view (u/id user) (u/id card))))))))))

(deftest card-read-view-count-test
  (mt/test-helpers-set-global-values!
    (mt/with-temporary-setting-values [synchronous-batch-updates true]
      (mt/with-temp [:model/User user {}
                     :model/Card card {:creator_id (u/id user)}]
        (testing "Card read events are recorded by a card's view_count"
          (is (= 0 (:view_count card))
              "view_count should be 0 before the event is published")
          (events/publish-event! :event/card-read {:object-id (:id card) :user-id (u/the-id user) :context :question})
          (is (= 1 (t2/select-one-fn :view_count :model/Card (:id card))))
          (events/publish-event! :event/card-read {:object-id (:id card) :user-id (u/the-id user) :context :question})
          (is (= 2 (t2/select-one-fn :view_count :model/Card (:id card)))))))))

(deftest dashboard-read-view-count-test
  (mt/test-helpers-set-global-values!
    (mt/with-temporary-setting-values [synchronous-batch-updates true]
      (mt/with-temp [:model/User          user      {}
                     :model/Dashboard     dashboard {:creator_id (u/id user)}
                     :model/Card          card      {:name "Dashboard Test Card"}
                     :model/DashboardCard _dashcard {:dashboard_id (u/id dashboard) :card_id (u/id card)}]
        (let [dashboard (t2/hydrate dashboard [:dashcards :card])]
          (testing "Dashboard read events are recorded by a dashboard's view_count"
            (is (= 0 (:view_count dashboard) (:view_count card))
                "view_count should be 0 before the event is published")
            (events/publish-event! :event/dashboard-read {:object-id (:id dashboard) :user-id (u/the-id user)})
            (is (= 1 (t2/select-one-fn :view_count :model/Dashboard (:id dashboard))))
            (is (= 0 (t2/select-one-fn :view_count :model/Card (:id card)))
                "view_count for cards on the dashboard should not be incremented")
            (events/publish-event! :event/dashboard-read {:object-id (:id dashboard) :user-id (u/the-id user)})
            (is (= 2 (t2/select-one-fn :view_count :model/Dashboard (:id dashboard))))))))))

(deftest table-read-view-count-test
  (mt/test-helpers-set-global-values!
    (mt/with-temporary-setting-values [synchronous-batch-updates true]
      (mt/with-temp [:model/User  user  {}
                     :model/Table table {}]
        (testing "Card read events are recorded by a card's view_count"
          (is (= 0 (:view_count table))
              "view_count should be 0 before the event is published")
          (events/publish-event! :event/table-read {:object table :user-id (u/the-id user)})
          (is (= 1 (t2/select-one-fn :view_count :model/Table (:id table)))
              "view_count should be incremented")
          (events/publish-event! :event/table-read {:object table :user-id (u/the-id user)})
          (is (= 2 (t2/select-one-fn :view_count :model/Table (:id table)))
              "view_count should be incremented"))))))

(deftest increment-view-counts!*-test
  (mt/with-temp [:model/Card  {card-1-id :id} {}
                 :model/Card  {card-2-id :id} {:view_count 2}
                 :model/Table {table-id :id}  {}]
    (let [call-count (atom 0)
          t2-query-orig t2/query]
      (testing "increment-view-counts!* update the view_count correctly"
        (with-redefs [t2/query (fn [& args] (swap! call-count inc) (apply t2-query-orig args))]
          (#'events.view-log/increment-view-counts!* [;; table-id : 1 views, card-id-1: 2 views, card-id 2: 2 views
                                                      {:model :model/Table :id table-id}
                                                      {:model :model/Card  :id card-1-id}
                                                      {:model :model/Card  :id card-1-id}
                                                      {:model :model/Card  :id card-2-id}
                                                      {:model :model/Card  :id card-2-id}]))
        (is (= 2 ;; one for update card, one for table
               @call-count)
            "and groups db calls by frequency")
        (is (= 1 (t2/select-one-fn :view_count :model/Table table-id))
            "view_count for table-id should be 1")
        (is (= 2 (t2/select-one-fn :view_count :model/Card card-1-id))
            "view_count for card-1 should be 2")
        (is (= 4 ;; 2 old + 2 new
               (t2/select-one-fn :view_count :model/Card card-2-id))
            "view_count for card-2 should be 2")))))

;;; ---------------------------------------- API tests begin -----------------------------------------

(deftest get-collection-view-log-test
  (mt/with-premium-features #{:audit-app}
    (testing "Collection reads via the API are recorded in the view_log"
      (mt/with-temp [:model/Collection coll {}]
        (testing "GET /api/collection/:id/items"
          (mt/user-http-request :crowberto :get 200 (format "collection/%s/items" (u/id coll)))
          (is (partial= {:user_id (mt/user->id :crowberto), :model "collection", :model_id (u/id coll)}
                        (latest-view (mt/user->id :crowberto) (u/id coll)))))))))

(deftest get-card-view-log-test
  (mt/with-premium-features #{:audit-app}
    (testing "Card reads (views) via the API are recorded in the view_log"
      (mt/with-temp [:model/Card card {:name "My Cool Card" :type :question}]
        (testing "GET /api/card/:id"
          (mt/user-http-request :crowberto :get 200 (format "card/%s" (u/id card)))
          (is (partial= {:user_id (mt/user->id :crowberto), :model "card", :model_id (u/id card), :context :question}
                        (latest-view (mt/user->id :crowberto) (u/id card)))))))))

(deftest get-dashboard-view-log-test
  (mt/with-premium-features #{:audit-app}
    (testing "Dashboard reads (views) via the API are recorded in the view_log"
      (mt/with-temp [:model/Dashboard dash {}]
        (testing "GET /api/dashboard/:id"
          (mt/user-http-request :crowberto :get 200 (format "dashboard/%s" (u/id dash)))
          (is (partial= {:user_id (mt/user->id :crowberto), :model "dashboard", :model_id (u/id dash)}
                        (latest-view (mt/user->id :crowberto) (u/id dash)))))))))

(deftest dashboard-card-query-view-log-test
  (mt/with-premium-features #{:audit-app}
    (testing "Running a query for a card in a dashboard is recorded in the view_log."
      (mt/with-temp [:model/Dashboard     dash     {}
                     :model/Card          card     {}
                     :model/DashboardCard dashcard {:dashboard_id (:id dash)
                                                    :card_id      (:id card)}]
        (testing "POST /api/dashboard/:dashboard-id/card/:card-id/query"
          (mt/user-http-request :crowberto :post 202
                                (api.dashboard-test/dashboard-card-query-url (:id dash) (:id card) (:id dashcard)))
          (is (partial= {:user_id (mt/user->id :crowberto), :model "card", :model_id (:id card), :context :dashboard}
                        (latest-view (mt/user->id :crowberto) (:id card)))))))))

(deftest get-public-card-logs-view-test
  (mt/with-premium-features #{:audit-app}
    (testing "Viewing a public card logs the correct view log event."
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (public-test/with-temp-public-card [card]
          (testing "GET /api/public/card/:uuid"
            (client/client :get 200 (str "public/card/" (:public_uuid card)))
            (is (partial= {:model "card", :model_id (:id card), :has_access true, :context :question}
                          (latest-view nil (:id card))))))))))

(deftest public-dashboard-card-query-view-log-test
  (mt/with-premium-features #{:audit-app}
    (testing "Running a query for a card in a public dashboard logs the correct view log event."
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (public-test/with-temp-public-dashboard-and-card [dash card dashcard]
          (testing "GET /api/public/dashboard/:uuid/card/:card-id"
            (client/client :get 202 (public-test/dashcard-url dash card dashcard))
            (is (partial= {:model "card", :model_id (:id card), :has_access true, :context :dashboard}
                          (latest-view nil (:id card))))))))))

(deftest get-public-dashboard-logs-view-test
  (mt/with-premium-features #{:audit-app}
    (testing "Viewing a public dashboard logs the correct view log event."
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (public-test/with-temp-public-dashboard [dash]
          (testing "GET /api/public/dashboard/:uuid"
            (client/client :get 200 (str "public/dashboard/" (:public_uuid dash)))
            (is (partial= {:model "dashboard", :model_id (:id dash), :has_access true}
                          (latest-view nil (:id dash))))))))))

(deftest get-embedded-card-embedding-logs-view-test
  (mt/with-premium-features #{:audit-app}
    (testing "Viewing an embedding logs the correct view log event."
      (embed-test/with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Card card {:enable_embedding true}]
          (testing "GET /api/embed/card/:token"
            (client/client :get 200 (embed-test/card-url card))
            (is (partial= {:model "card", :model_id (:id card), :has_access true}
                          (latest-view nil (:id card))))))))))

(deftest embedded-dashboard-card-query-view-log-test
  (mt/with-premium-features #{:audit-app}
    (testing "Running a query for a card in a public dashboard logs the correct view log event."
      (embed-test/with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Dashboard dash {:enable_embedding true}
                       :model/Card          card     {}
                       :model/DashboardCard dashcard {:dashboard_id (:id dash)
                                                      :card_id      (:id card)}]
          (testing "GET /dashboard/:token/dashcard/:dashcard-id/card/:card-id"
            (client/client :get 202 (embed-test/dashcard-url dashcard))
            (is (partial= {:model "card", :model_id (:id card), :has_access true, :context :dashboard}
                          (latest-view nil (:id card))))))))))

(deftest get-embedded-dashboard-logs-view-test
  (mt/with-premium-features #{:audit-app}
    (testing "Viewing an embedding logs the correct view log event."
      (embed-test/with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Dashboard dash {:enable_embedding true}]
          (testing "GET /api/embed/dashboard/:token"
            (client/client :get 200 (embed-test/dashboard-url dash))
            (is (partial= {:model "dashboard", :model_id (:id dash), :has_access true}
                          (latest-view nil (:id dash))))))))))

;;; ---------------------------------------- API tests end -----------------------------------------
