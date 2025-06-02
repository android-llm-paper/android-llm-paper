import sqlite3, os
from flask import g, Flask, render_template, jsonify, request
from flask_pymongo import PyMongo
from bson import json_util

app = Flask(__name__)
app.config["MONGO_URI"] = "mongodb://localhost:27017/binder_analyzer"
mongo = PyMongo(app)


@app.route("/", methods=["GET"])
def index():
    with mongo.db.firmware.find() as cursor:
        fws = []
        for fw in cursor:
            analyze_status = {

            }
            if not fw['isBaseline']:
                agg = [
                    {'$match': {'firmwareId': fw['_id'], 'results': {'$exists': True}}},
                    {'$addFields': {'resultsCount': {'$size': {'$objectToArray': '$results'}}}},
                    {'$group': {'_id': '$resultsCount', 'count': {'$count': {}}}}
                ]
                with mongo.db.binder_interface.aggregate(agg) as cursor:
                    for x in cursor:
                        analyze_status[x['_id']] = x['count']
            fws.append(
                {
                    "obj": fw,
                    "total": mongo.db.binder_interface.count_documents(
                        {"firmwareId": fw["_id"]}
                    ),
                    "custom": mongo.db.binder_interface.count_documents(
                        {"firmwareId": fw["_id"], "inBaseline": False}
                    ),
                    "customAndAccessible": mongo.db.binder_interface.count_documents(
                        {
                            "firmwareId": fw["_id"],
                            "inBaseline": False,
                            "isAccessible": True,
                        }
                    ),
                    "analyzeStatus": analyze_status
                }
            )
        return render_template("index.html", firmwares=fws)


@app.route("/firmware/<ObjectId:fw_id>", methods=["GET"])
def list_interfaces(fw_id):
    firmware = mongo.db.firmware.find_one_or_404({"_id": fw_id})
    agg = [
        # {"$match": {"results": {"$exists": True}, "firmwareId": fw_id, "ignored": {"$ne": True}}},
        {"$match": {"results": {"$exists": True}, "firmwareId": fw_id}},
        {"$addFields": {"result": {"$objectToArray": "$results"}}},
        {"$unwind": {"path": "$result"}},
        {
            "$group": {
                "_id": "$_id",
                "firmwareId": {"$first": "$firmwareId"},
                "containsSecurityCheck": {"$sum": "$result.v.containsSecurityCheck"},
                "clearsCallingIdentity": {"$sum": "$result.v.clearsCallingIdentity"},
                "isNotEmpty": {"$sum": "$result.v.isNotEmpty"},
                "sensitive": {"$sum": "$result.v.sensitive"},
                "results": {"$first": "$results"},
                "serviceName": {"$first": "$serviceName"},
                "callee": {"$first": "$callee"},
                "source": {"$first": "$source"},
            }
        },
        {
            "$sort": {
                "containsSecurityCheck": 1,
                "clearsCallingIdentity": -1,
                "serviceName": 1,
            }
        },
    ]
    with mongo.db.binder_interface.aggregate(agg) as cursor:
        return render_template("list.html", interfaces=cursor, firmware=firmware)

@app.route("/api/interface/<ObjectId:if_id>", methods=["GET"])
def api_details(if_id):
    obj = mongo.db.binder_interface.find_one_or_404({"_id": if_id})
    return jsonify({
        "source": obj['source'],
        "results": obj['results'],
        "callee": obj['callee'],
        "caller": obj['caller'],
    })

@app.route("/api/interface/<ObjectId:if_id>/ignore", methods=["PUT"])
def api_ignore(if_id):
    mongo.db.binder_interface.update_one({"_id": if_id}, {"$set": {"ignored": True}})
    return jsonify({})

@app.route("/api/interface/<ObjectId:if_id>/ignore", methods=["DELETE"])
def api_unignore(if_id):
    mongo.db.binder_interface.update_one({"_id": if_id}, {"$set": {"ignored": False}})
    return jsonify({})

# @app.route("/list", methods=["GET"])
# def list_fns():
#     fns = query_db(
#         """
#         SELECT id, service_name, txn_code, callee, callee_name
#         COUNT(*) AS count,
#         SUM(is_system_only) AS sum_sys_only,
#         SUM(contains_security_check) AS sum_sec,
#         SUM(clears_calling_identity) AS sum_ident,
#         SUM(is_not_empty) AS sum_not_empty
#         FROM llm_results GROUP BY service_name, txn_code
#         ORDER BY sum_sec ASC, sum_ident DESC, sum_not_empty DESC, service_name ASC
#         """
#     )
#     return jsonify(list(map(dict, fns)))

# @app.route("/api/code", methods=["GET"])
# def get_code():
#     fn = query_db("SELECT callee, source, model, description FROM llm_results WHERE service_name = ? AND txn_code = ?", (request.args['service_name'], request.args['txn_code']))
#     source = fn[0]['callee'].split(": ")[1].split(" ")[0] + ' ' + fn[0]['source']
#     desc = {}
#     for x in fn:
#         desc[x['model']] = x['description']

#     return jsonify({
#         "code": source,
#         "description": desc
#     })

# @app.route("/api/mark_done", methods=["POST"])
# def mark_done():
#     db = get_db()
#     db.execute("UPDATE llm_results SET done = 1 WHERE service_name = ? AND txn_code = ?", (request.args['service_name'], request.args['txn_code']))
#     db.commit()
#     return jsonify({})
