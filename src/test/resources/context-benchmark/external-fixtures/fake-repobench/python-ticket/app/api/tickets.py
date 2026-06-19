from app.services.ticket_service import TicketService


ticket_service = TicketService()


def create_ticket(payload):
    return ticket_service.create_ticket(payload)
