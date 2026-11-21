// Run as a MongoDB administrator. Existing passwords are preserved.
const database = db.getSiblingDB("cold_storage");
const backendCollections = [
    "audit_system", "audit_dataset", "audit_product",
    "audit_prediction", "audit_warning", "audit_access"
];

function privilege(collection, actions) {
    return {resource: {db: database.getName(), collection}, actions};
}

function applyRole(role, privileges) {
    if (database.getRole(role)) {
        database.updateRole(role, {privileges, roles: []});
    } else {
        database.createRole({role, privileges, roles: []});
    }
}

function applyUser(user, role) {
    if (database.getUser(user)) {
        database.updateUser(user, {roles: [role]});
    } else {
        print(`Choose a password for ${user}`);
        database.createUser({user, pwd: passwordPrompt(), roles: [role]});
    }
}

for (const collection of ["audit_logs", ...backendCollections, "audit_ml"]) {
    database.getCollection(collection).createIndex({timestamp: -1, _id: -1}, {name: "audit_time_id"});
    database.getCollection(collection).createIndex({eventType: 1, timestamp: -1}, {name: "event_time"});
}

applyRole("coldStorageBackend", [
    ...backendCollections.map(collection => privilege(collection, ["find", "insert"])),
    privilege("audit_logs", ["find", "createIndex", "listIndexes"]),
    privilege("audit_ml", ["find"]),
    ...["datasets", "products"].map(collection =>
        privilege(collection, ["find", "insert", "update", "remove", "createIndex", "listIndexes"])),
    privilege("predictions", ["find", "insert", "remove", "createIndex", "listIndexes"])
]);
applyRole("coldStorageMlAuditWriter", [privilege("audit_ml", ["insert"])]);
applyUser("cold_storage_app", "coldStorageBackend");
applyUser("cold_storage_ml", "coldStorageMlAuditWriter");
